package com.boshys.bteutils.data;

import com.boshys.bteutils.BoshysBTEUtils;
import net.minecraft.util.math.Vec3d;

public class MarkerData {

    public static class TeleportMarker {
        public Vec3d position;
        public int colour;
        public float scale;
        public float opacity;
        /** Radius of an optional circle drawn around this marker. 0 = no circle. */
        public double circleRadius;

        public TeleportMarker(Vec3d position, int colour, float scale, float opacity) {
            this.position = position;
            this.colour = colour;
            this.scale = scale;
            this.opacity = opacity;
            this.circleRadius = 0;
        }
    }

    public static class MarkerConnection {
        public final TeleportMarker marker1;
        public final TeleportMarker marker2;

        public MarkerConnection(TeleportMarker marker1, TeleportMarker marker2) {
            this.marker1 = marker1;
            this.marker2 = marker2;
        }
    }

    public static class SavedMarkerData {
        public double x, y, z;
        public int colour;
        public float scale;
        public float opacity;
        /** Persisted circle radius — 0 means no circle. */
        public double circleRadius;

        public SavedMarkerData(double x, double y, double z, int colour, float scale, float opacity) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.colour = colour;
            this.scale = scale;
            this.opacity = opacity;
            this.circleRadius = 0;
        }

        /** Full constructor including circle radius. */
        public SavedMarkerData(double x, double y, double z, int colour, float scale, float opacity, double circleRadius) {
            this(x, y, z, colour, scale, opacity);
            this.circleRadius = circleRadius;
        }
    }

    public static class SavedConnectionData {
        public int fromIndex;
        public int toIndex;

        public SavedConnectionData(int fromIndex, int toIndex) {
            this.fromIndex = fromIndex;
            this.toIndex = toIndex;
        }
    }

    public static class SavedMarkerFile {
        public String name;
        public long lastModified;
        public java.util.List<SavedMarkerData> markers;
        public java.util.List<SavedConnectionData> connections;

        public SavedMarkerFile(String name, long lastModified, java.util.List<SavedMarkerData> markers) {
            this(name, lastModified, markers, new java.util.ArrayList<>());
        }

        public SavedMarkerFile(String name, long lastModified, java.util.List<SavedMarkerData> markers, java.util.List<SavedConnectionData> connections) {
            this.name = name;
            this.lastModified = lastModified;
            this.markers = markers;
            this.connections = connections != null ? connections : new java.util.ArrayList<>();
        }
    }

    public static class KmlPoint {
        public final double longitude;
        public final double latitude;
        public final double altitude;

        public KmlPoint(double longitude, double latitude, double altitude) {
            this.longitude = longitude;
            this.latitude = latitude;
            this.altitude = altitude;
        }
    }

    // Static methods for marker operations
    public static TeleportMarker addMarker(Vec3d pos) {
        TeleportMarker marker = new TeleportMarker(pos, BoshysBTEUtils.getConfig().markerColour, BoshysBTEUtils.getConfig().markerScale, BoshysBTEUtils.getConfig().markerOpacity);
        BoshysBTEUtils.markers.add(marker);
        return marker;
    }

    public static void updateMarkerDesign(TeleportMarker marker) {
        marker.colour = BoshysBTEUtils.getConfig().markerColour;
        marker.scale = BoshysBTEUtils.getConfig().markerScale;
        marker.opacity = BoshysBTEUtils.getConfig().markerOpacity;
    }

    public static void deleteMarker(TeleportMarker marker) {
        BoshysBTEUtils.markerConnections.removeIf(conn -> conn.marker1 == marker || conn.marker2 == marker);
        BoshysBTEUtils.markers.remove(marker);
        BoshysBTEUtils.selectedMarkers.remove(marker);
        BoshysBTEUtils.markerOrigins.remove(marker);
        BoshysBTEUtils.markerOriginalPositions.remove(marker);
        if (BoshysBTEUtils.lastAddedMarker == marker) {
            BoshysBTEUtils.lastAddedMarker = null;
        }
    }

    public static void clearAllMarkers() {
        BoshysBTEUtils.markers.clear();
        BoshysBTEUtils.markerConnections.clear();
        BoshysBTEUtils.selectedMarkers.clear();
        BoshysBTEUtils.lastAddedMarker = null;
        BoshysBTEUtils.markerOrigins.clear();
        BoshysBTEUtils.markerOriginalPositions.clear();
    }

    public static void connectMarkers(TeleportMarker m1, TeleportMarker m2) {
        if (m1 == m2) return;
        if (!areMarkersConnected(m1, m2)) {
            BoshysBTEUtils.markerConnections.add(new MarkerConnection(m1, m2));
        }
    }

    public static void disconnectMarkers(TeleportMarker m1, TeleportMarker m2) {
        BoshysBTEUtils.markerConnections.removeIf(conn ->
                (conn.marker1 == m1 && conn.marker2 == m2) ||
                        (conn.marker1 == m2 && conn.marker2 == m1)
        );
    }

    public static boolean areMarkersConnected(TeleportMarker m1, TeleportMarker m2) {
        for (MarkerConnection conn : BoshysBTEUtils.markerConnections) {
            if ((conn.marker1 == m1 && conn.marker2 == m2) ||
                    (conn.marker1 == m2 && conn.marker2 == m1)) {
                return true;
            }
        }
        return false;
    }

    public static void handleAutoConnect(TeleportMarker newMarker) {
        if (BoshysBTEUtils.lastAddedMarker != null && BoshysBTEUtils.lastAddedMarker != newMarker) {
            connectMarkers(BoshysBTEUtils.lastAddedMarker, newMarker);
        }
        BoshysBTEUtils.selectedMarkers.clear();
        BoshysBTEUtils.selectedMarkers.add(newMarker);
        BoshysBTEUtils.lastAddedMarker = newMarker;
    }
}