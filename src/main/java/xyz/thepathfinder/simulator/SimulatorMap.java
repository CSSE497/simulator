package xyz.thepathfinder.simulator;

import com.google.gson.JsonObject;
import com.lynden.gmapsfx.GoogleMapView;
import com.lynden.gmapsfx.MapComponentInitializedListener;
import com.lynden.gmapsfx.javascript.object.GoogleMap;
import com.lynden.gmapsfx.javascript.object.LatLong;
import com.lynden.gmapsfx.javascript.object.MVCArray;
import com.lynden.gmapsfx.javascript.object.MapOptions;
import com.lynden.gmapsfx.javascript.object.MapTypeIdEnum;
import com.lynden.gmapsfx.javascript.object.Marker;
import com.lynden.gmapsfx.javascript.object.MarkerOptions;
import com.lynden.gmapsfx.shapes.Polyline;
import com.lynden.gmapsfx.shapes.PolylineOptions;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.thepathfinder.android.Cluster;
import xyz.thepathfinder.android.ClusterListener;
import xyz.thepathfinder.android.Pathfinder;
import xyz.thepathfinder.android.Transport;
import xyz.thepathfinder.android.TransportStatus;
import xyz.thepathfinder.gmaps.Coordinate;

import java.io.IOException;
import java.util.List;

import static java.util.stream.Collectors.toList;

public class SimulatorMap extends Application implements MapComponentInitializedListener {
    private static final Logger log = LoggerFactory.getLogger(SimulatorMap.class);

    private static final Configuration config;
    private static final String clusterId;
    private static final String applicationId;
    private static final String idToken;
    private static final int mpg;
    private static final int capacity;
    private static final List<String> addresses;
    static {
        try {
            config = new PropertiesConfiguration("config.properties");
            addresses = config.getList("loop.address").stream().map(Object::toString).collect(toList());
            applicationId = config.getString("application_id").trim();
            idToken = config.getString("id_token").trim();
            clusterId = config.getString("cluster_id").trim();
            mpg = config.getInt("mpg");
            capacity = config.getInt("capacity");
            log.info("Loop is " + addresses);
        } catch (ConfigurationException e) {
            log.error("Failed to load config.properties");
            throw new RuntimeException(e);
        }
    }

    private Pathfinder pf;
    private SimulatedTransport simulatedTransport;

    GoogleMapView mapView;
    GoogleMap map;

    @Override public void start(Stage stage) throws Exception {
        mapView = new GoogleMapView();
        mapView.addMapInializedListener(this);
        Scene scene = new Scene(mapView);
        stage.setTitle("Google Maps");
        stage.setScene(scene);
        stage.show();
        simulatedTransport = SimulatedTransport.create(addresses);
        // Get google id token
        pf = new Pathfinder(applicationId, idToken);
        pf.connect();
        Cluster c = pf.getCluster(clusterId);
        JsonObject metadata = new JsonObject();
        metadata.addProperty("mpg", mpg);
        metadata.addProperty("chimney", capacity);

        Transport transport = c.createTransport(simulatedTransport.start().lat, simulatedTransport.start().lng, TransportStatus.ONLINE, metadata);
        simulatedTransport = SimulatedTransport.create(addresses);
        simulatedTransport.addTransport(transport);
        transport.addListener(simulatedTransport);
        transport.create();
        transport.routeSubscribe();

        Timeline timeline = new Timeline(new KeyFrame(Duration.millis(2000), new EventHandler<ActionEvent>() {
            Marker m;
            boolean routeDrawn = false;
            @Override public void handle(ActionEvent event) {
                if (m != null) {
                    map.removeMarker(m);
                }
                if (map != null && !routeDrawn) {
                    addLoop(simulatedTransport.loopPath());
                    routeDrawn = true;
                }
                if (map != null) {
                    try {
                        m = addMarker(simulatedTransport.next());
                    } catch (IOException e) {
                        log.error("Oops, I failed to get the next simulated transport coordinate");
                        e.printStackTrace();
                    }
                }
            }
        }));
        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.play();
    }

    @Override public void stop() {
        simulatedTransport.stop();
    }

    public void mapInitialized() {
        Coordinate start = null;
        try {
            start = simulatedTransport.next();
        } catch (IOException e) {
            log.error("Oops, I failed to get the next simulated transport coordinate");
            e.printStackTrace();
        }
        MapOptions mapOptions = new MapOptions();
        mapOptions.center(new LatLong(start.lat, start.lng))
            .mapType(MapTypeIdEnum.ROADMAP)
            .overviewMapControl(false)
            .panControl(false)
            .rotateControl(false)
            .scaleControl(false)
            .streetViewControl(false)
            .zoomControl(false)
            .zoom(12);
        map = mapView.createMap(mapOptions);
    }

    private Marker addMarker(Coordinate c) {
        MarkerOptions markerOptions = new MarkerOptions();
        markerOptions.position(new LatLong(c.lat, c.lng))
                .visible(Boolean.TRUE);
        Marker marker = new Marker(markerOptions);
        map.addMarker(marker);
        return marker;
    }

    private void addLoop(List<Coordinate> points) {
        MVCArray path = new MVCArray(points.stream().map(c -> new LatLong(c.lat, c.lng))
                .collect(toList()).toArray());
        PolylineOptions polylineOptions = new PolylineOptions()
                .path(path);
        map.addMapShape(new Polyline(polylineOptions));
    }

    public static void main(String args[]) {
        launch(args);
    }
}
