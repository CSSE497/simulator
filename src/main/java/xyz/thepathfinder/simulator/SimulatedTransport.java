package xyz.thepathfinder.simulator;

import com.google.gson.Gson;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.thepathfinder.android.Action;
import xyz.thepathfinder.android.ActionStatus;
import xyz.thepathfinder.android.Commodity;
import xyz.thepathfinder.android.Route;
import xyz.thepathfinder.android.Transport;
import xyz.thepathfinder.android.TransportListener;
import xyz.thepathfinder.android.TransportStatus;
import xyz.thepathfinder.gmaps.Coordinate;
import xyz.thepathfinder.gmaps.Directions;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static java.util.stream.Collectors.toList;
import static xyz.thepathfinder.gmaps.Coordinate.distance;
import static xyz.thepathfinder.gmaps.Coordinate.moveTowards;

class SimulatedTransport extends TransportListener {
    private static final Logger log = LoggerFactory.getLogger(SimulatedTransport.class);
    private static final String gmapsApiKey = "AIzaSyAc73g_Rp73AdJQKRDgaI1ErvewEwbizP8";
    private static final Gson gson = new Gson();
    private static final OkHttpClient client = new OkHttpClient();
    private static final double DELTA = 0.002;          // Movement distance
    private static final double EPSILON = 2 * DELTA;    // Pickup/dropoff distance
    private final List<Coordinate> loopPath;
    private List<Coordinate> actionPath = new ArrayList<>();
    private List<Action> actions = new ArrayList<>();
    private Coordinate current;
    private int nextIndex;
    private volatile Transport transport;
    private boolean waiting = true;

    private SimulatedTransport(List<Coordinate> loopPath) {
        this.loopPath = loopPath;
        this.current = loopPath.get(0);
        nextIndex = 1;
    }

    static SimulatedTransport create(List<String> addressLoop) throws IOException {
        return new SimulatedTransport(pathFromLoop(addressLoop));
    }

    static List<Coordinate> pathFromLoop(List<String> addressLoop) throws IOException {
        log.info(String.format("Creating loop from %d addresses: %s", addressLoop.size(), addressLoop));
        List<Coordinate> path = new ArrayList<>();
        for (int i = 0; i < addressLoop.size(); i++) {
            Directions d = getDirections(addressLoop.get(i), addressLoop.get((i + 1) % addressLoop.size()));
            path.addAll(d.coordinates());
        }
        return path;
    }

    Coordinate start() {
        return this.loopPath.get(0);
    }

    Coordinate next() throws IOException {
        if (waiting) return this.current;
        if (transport != null){
            move(DELTA);
            notifyPathfinder();
        }
        return this.current;
    }

    List<Coordinate> loopPath() {
        return this.loopPath;
    }

    void stop() {
        if (transport != null) {
            log.info("Taking transport offline");
            transport.updateStatus(TransportStatus.OFFLINE);
        }
    }

    void addTransport(Transport transport) {
        this.transport = transport;
    }

    private void notifyPathfinder() {
        log.info("Updating Pathfinder location to " + current);
        transport.updateLocation(current.lat, current.lng);
    }

    private static Directions getDirections(String start, String end) throws IOException {
        String url = "https://maps.googleapis.com/maps/api/directions/json"
                + "?origin=" + start
                + "&destination=" + end
                + "&key=" + gmapsApiKey;
        return getDirections(url);
    }

    private static Directions getDirections(Coordinate start, Coordinate end) throws IOException {
        String url = "https://maps.googleapis.com/maps/api/directions/json"
                + "?origin=" + start.lat + "," + start.lng
                + "&destination=" + end.lat + "," + end.lng
                + "&key=" + gmapsApiKey;
        return getDirections(url);
    }

    private static Directions getDirections(String url) throws IOException {
        log.info(String.format("Requesting directions from url: %s", url));
        Request request = new Request.Builder()
                .url(url)
                .build();
        Response response = client.newCall(request).execute();
        return gson.fromJson(response.body().charStream(), Directions.class);
    }

    private synchronized void move(double delta) throws IOException {
        if (delta < 0) return;
        List<Coordinate> path;
        if (actionPath.isEmpty()) {     // No Pathfinder actions, currently on loop.
            log.info("No actions, moving on loop");
            path = loopPath;
        } else if (actions.isEmpty()){  // No Pathfinder actions, returning to loop.
            log.info("No actions, returning to loop");
            path = actionPath;
        } else {
            log.info("Moving on action path");
            if (distance(current, coordinate(actions.get(0))) < EPSILON) {
                Action a = actions.get(0);
                Commodity commodity = a.getCommodity();
                if (a.getStatus() == ActionStatus.PICK_UP) {
                    commodity.updatePickedUp(transport);
                } else if (a.getStatus() == ActionStatus.DROP_OFF) {
                    commodity.updateDroppedOff();
                }
                actions.remove(0);
                if (actions.isEmpty()) {                    // Completed all Pathfinder actions, returning to loop.
                    log.info("Completed final action, returning to route");
                    actionPath = getDirections(current, start()).coordinates();
                } else {
                    log.info("Completed action, setting course towards next action");
                    actionPath = getDirections(current, coordinate(actions.get(0))).coordinates();
                }
                nextIndex = 1;
            }
            path = actionPath;
        }
        double distanceToNext = distance(current, path.get(nextIndex));
        if (distanceToNext > delta) {
            current = moveTowards(path.get(nextIndex), current, delta / 2);
        } else {
            current = path.get(nextIndex);
            nextIndex = (nextIndex + 1) % path.size();
        }
    }

    private static Coordinate coordinate(Action a) {
        return new Coordinate(a.getLatitude(), a.getLongitude());
    }

    @Override
    public void routed(Route route) {
        waiting = false;
        log.info(String.format("Received new route: {}", route.getActions()));
        List<Action> actions = route.getActions().stream().filter(a -> a.getStatus() != ActionStatus.START).collect(toList());
        try {
            if (!actions.isEmpty() && (this.actions.isEmpty() || actions.get(0).getLatitude() != this.actions.get(0).getLatitude())) {
                log.info("Switching to new actions from Pathfinder");
                this.actions = actions;
                actionPath = getDirections(current, coordinate(actions.get(0))).coordinates();
                nextIndex = 1;
            } else {
                this.actions = actions;
                log.info("Using new actions from Pathfinder without asking for directions");
            }
        } catch (IOException e) {
            log.error("Oops, I failed to get GMaps directions");
            e.printStackTrace();
        }
    }
}
