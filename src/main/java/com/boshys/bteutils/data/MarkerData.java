package com.boshys.bteutils.data;

import com.boshys.bteutils.BoshysBTEUtils;
import net.minecraft.world.phys.Vec3;

public class MarkerData {

    public static class TeleportMarker {
        public Vec3 position;
        public int colour;
        public float scale;
        public float opacity;
        /** Radius of an optional circle drawn around this marker. 0 = no circle. */
        public double circleRadius;
        /** Per-marker circle colour (overrides global default if set). -1 = use default. */
        public int circleColour;
        /** Per-marker circle opacity (overrides global default if set). -1 = use default. */
        public float circleOpacity;
        /** Per-marker circle thickness (overrides global default if set). -1 = use default. */
        public float circleThickness;
        /** Per-marker circle segment percent (overrides global default if set). -1 = use default. */
        public float circleSegmentPercent;

        public TeleportMarker(Vec3 position, int colour, float scale, float opacity) {
            this.position = position;
            this.colour = colour;
            this.scale = scale;
            this.opacity = opacity;
            this.circleRadius = 0;
            this.circleColour = -1;
            this.circleOpacity = -1;
            this.circleThickness = -1;
            this.circleSegmentPercent = -1.0f;
        }
    }

    public static class MarkerConnection {
        public final TeleportMarker marker1;
        public final TeleportMarker marker2;
        /** Per-connection line colour (overrides global default if set). -1 = use default. */
        public int lineColour;
        /** Per-connection line opacity (overrides global default if set). -1 = use default. */
        public float lineOpacity;
        /** Per-connection line thickness (overrides global default if set). -1 = use default. */
        public float lineThickness;

        public MarkerConnection(TeleportMarker marker1, TeleportMarker marker2) {
            this.marker1 = marker1;
            this.marker2 = marker2;
            this.lineColour = -1;
            this.lineOpacity = -1;
            this.lineThickness = -1;
        }
    }

    public static class SavedMarkerData {
        public double x, y, z;
        public int colour;
        public float scale;
        public float opacity;
        /** Persisted circle radius — 0 means no circle. */
        public double circleRadius;
        /** Persisted per-marker circle colour. -1 = use default. */
        public int circleColour;
        /** Persisted per-marker circle opacity. -1 = use default. */
        public float circleOpacity;
        /** Persisted per-marker circle thickness. -1 = use default. */
        public float circleThickness;
        /** Persisted per-marker circle segment percent. -1 = use default. */
        public float circleSegmentPercent;

        public SavedMarkerData(double x, double y, double z, int colour, float scale, float opacity) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.colour = colour;
            this.scale = scale;
            this.opacity = opacity;
            this.circleRadius = 0;
            this.circleColour = -1;
            this.circleOpacity = -1;
            this.circleThickness = -1;
            this.circleSegmentPercent = -1.0f;
        }

        /** Full constructor including circle properties. */
        public SavedMarkerData(double x, double y, double z, int colour, float scale, float opacity, double circleRadius) {
            this(x, y, z, colour, scale, opacity);
            this.circleRadius = circleRadius;
        }

        /** Full constructor with all circle properties. */
        public SavedMarkerData(double x, double y, double z, int colour, float scale, float opacity,
                               double circleRadius, int circleColour, float circleOpacity, float circleThickness, float circleSegmentPercent) {
            this(x, y, z, colour, scale, opacity, circleRadius);
            this.circleColour = circleColour;
            this.circleOpacity = circleOpacity;
            this.circleThickness = circleThickness;
            this.circleSegmentPercent = circleSegmentPercent;
        }

    }

    public static class SavedConnectionData {
        public int fromIndex;
        public int toIndex;
        /** Persisted per-connection line colour. -1 = use default. */
        public int lineColour;
        /** Persisted per-connection line opacity. -1 = use default. */
        public float lineOpacity;
        /** Persisted per-connection line thickness. -1 = use default. */
        public float lineThickness;

        public SavedConnectionData(int fromIndex, int toIndex) {
            this.fromIndex = fromIndex;
            this.toIndex = toIndex;
            this.lineColour = -1;
            this.lineOpacity = -1;
            this.lineThickness = -1;
        }

        public SavedConnectionData(int fromIndex, int toIndex, int lineColour, float lineOpacity, float lineThickness) {
            this(fromIndex, toIndex);
            this.lineColour = lineColour;
            this.lineOpacity = lineOpacity;
            this.lineThickness = lineThickness;
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
    public static TeleportMarker addMarker(net.minecraft.world.phys.Vec3 pos) {
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
        if (BoshysBTEUtils.lastAutoConnectMarker == marker) {
            BoshysBTEUtils.lastAutoConnectMarker = null;
        }
    }

    public static void clearAllMarkers() {
        BoshysBTEUtils.markers.clear();
        BoshysBTEUtils.markerConnections.clear();
        BoshysBTEUtils.selectedMarkers.clear();
        BoshysBTEUtils.lastAddedMarker = null;
        BoshysBTEUtils.lastAutoConnectMarker = null;
        BoshysBTEUtils.markerOrigins.clear();
        BoshysBTEUtils.markerOriginalPositions.clear();
    }

    public static MarkerConnection connectMarkers(TeleportMarker m1, TeleportMarker m2) {
        if (m1 == m2) return null;
        if (!areMarkersConnected(m1, m2)) {
            MarkerConnection conn = new MarkerConnection(m1, m2);
            BoshysBTEUtils.markerConnections.add(conn);
            return conn;
        }
        // Already connected - find and return existing connection
        for (MarkerConnection conn : BoshysBTEUtils.markerConnections) {
            if ((conn.marker1 == m1 && conn.marker2 == m2) ||
                    (conn.marker1 == m2 && conn.marker2 == m1)) {
                return conn;
            }
        }
        return null;
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
        // Connect to selected marker(s) if any, otherwise connect to last auto-connect target
        if (!BoshysBTEUtils.selectedMarkers.isEmpty()) {
            // Connect the new marker to ALL currently selected markers
            for (MarkerData.TeleportMarker selected : BoshysBTEUtils.selectedMarkers) {
                if (selected != newMarker) {
                    connectMarkers(selected, newMarker);
                }
            }
        } else if (BoshysBTEUtils.lastAutoConnectMarker != null
                && BoshysBTEUtils.lastAutoConnectMarker != newMarker
                && BoshysBTEUtils.markers.contains(BoshysBTEUtils.lastAutoConnectMarker)) {
            connectMarkers(BoshysBTEUtils.lastAutoConnectMarker, newMarker);
        }
        BoshysBTEUtils.selectedMarkers.clear();
        BoshysBTEUtils.selectedMarkers.add(newMarker);
        BoshysBTEUtils.lastAddedMarker = newMarker;
        BoshysBTEUtils.lastAutoConnectMarker = newMarker;
    }
}