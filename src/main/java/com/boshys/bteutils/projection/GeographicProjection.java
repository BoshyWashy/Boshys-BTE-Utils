// File: GeographicProjection.java
/* package com.boshys.bteutils.projection;

/**
 * Implements geographic projection using the same calculations as TerraMinusMinus.
 * Supports multiple projection types with configurable scale factors.
 *
 * This class handles the conversion between real-world geographic coordinates
 * (latitude/longitude) and Minecraft world coordinates (X/Z).
 *
 * Based on TerraMinusMinus projection system:
 * - Equirectangular: Simple lat/lon to y/x projection
 * - WebMercator: Standard web mapping projection
 * - BTE Conformal Dymaxion: Complex icosahedron-based projection (simplified here)

public class GeographicProjection {

    // Earth's circumference at equator in meters (TerraConstants.EARTH_CIRCUMFERENCE)
    public static final double EARTH_CIRCUMFERENCE = 40075017.0;

    // Earth's polar circumference in meters (TerraConstants.EARTH_POLAR_CIRCUMFERENCE)
    public static final double EARTH_POLAR_CIRCUMFERENCE = 40008000.0;

    // Square root of 3 (MathUtils.ROOT3)
    public static final double ROOT3 = Math.sqrt(3.0);

    // 2 * PI (MathUtils.TAU)
    public static final double TAU = 2.0 * Math.PI;

    // Dymaxion projection constant ARC (from DymaxionProjection)
    // ARC = 2 * asin(sqrt(5 - sqrt(5)) / sqrt(10))
    public static final double DYMAXION_ARC = 2.0 * Math.asin(Math.sqrt(5.0 - Math.sqrt(5.0)) / Math.sqrt(10.0));

    // BTE Dymaxion rotation angle in radians (THETA = -150 degrees)
    public static final double BTE_THETA = Math.toRadians(-150.0);
    public static final double BTE_SIN_THETA = Math.sin(BTE_THETA);
    public static final double BTE_COS_THETA = Math.cos(BTE_THETA);

    // Scale factors - these should match your server's configuration
    // Default BTE scale is typically around 1000.0 (1000 blocks = 1 degree at equator roughly)
    private double scaleX = 1000.0;
    private double scaleY = 1000.0;

    // Projection type
    private ProjectionType projectionType = ProjectionType.EQUIRECTANGULAR;

    /**
     * Supported projection types matching TerraMinusMinus

    public enum ProjectionType {
        EQUIRECTANGULAR,    // Simple plate carrée (lat/lon directly to y/x)
        WEB_MERCATOR,       // Web Mercator (EPSG:3857)
        BTE_CONFORMAL_DYMAXION  // BuildTheEarth's modified Dymaxion (simplified)
    }

    /**
     * Creates a projection with default scale (1000.0, 1000.0)

    public GeographicProjection() {
    }

    /**
     * Creates a projection with specified scale factors
     *
     * @param scaleX Scale factor for X axis (East-West)
     * @param scaleY Scale factor for Z axis (North-South)

    public GeographicProjection(double scaleX, double scaleY) {
        this.scaleX = scaleX;
        this.scaleY = scaleY;
    }

    /**
     * Creates a projection with specified type and scale
     *
     * @param type Projection type
     * @param scaleX Scale factor for X axis
     * @param scaleY Scale factor for Z axis

    public GeographicProjection(ProjectionType type, double scaleX, double scaleY) {
        this.projectionType = type;
        this.scaleX = scaleX;
        this.scaleY = scaleY;
    }

    /**
     * Sets the scale factors (like ScaleProjectionTransform in TerraMinusMinus)
     *
     * @param x Scale for X axis
     * @param y Scale for Y axis

    public void setScale(double x, double y) {
        this.scaleX = x;
        this.scaleY = y;
    }

    /**
     * Sets the projection type
     *
     * @param type Projection type

    public void setProjectionType(ProjectionType type) {
        this.projectionType = type;
    }

    /**
     * Gets the current scale X

    public double getScaleX() {
        return scaleX;
    }

    /**
     * Gets the current scale Y

    public double getScaleY() {
        return scaleY;
    }

    /**
     * Converts geographic coordinates to Minecraft coordinates.
     * This is the main entry point equivalent to TerraMinusMinus GeographicProjection.fromGeo()
     *
     * @param longitude Longitude in degrees (East positive, West negative)
     * @param latitude Latitude in degrees (North positive, South negative)
     * @return double array where [0] = X coordinate, [1] = Z coordinate
     * @throws OutOfProjectionBoundsException if coordinates are outside valid projection domain

    public double[] fromGeo(double longitude, double latitude) throws OutOfProjectionBoundsException {
        // Validate input coordinates
        if (longitude < -180.0 || longitude > 180.0) {
            throw new OutOfProjectionBoundsException("Longitude out of range: " + longitude);
        }
        if (latitude < -90.0 || latitude > 90.0) {
            throw new OutOfProjectionBoundsException("Latitude out of range: " + latitude);
        }

        double[] result;

        switch (projectionType) {
            case WEB_MERCATOR:
                result = fromGeoWebMercator(longitude, latitude);
                break;
            case BTE_CONFORMAL_DYMAXION:
                result = fromGeoBteDymaxion(longitude, latitude);
                break;
            case EQUIRECTANGULAR:
            default:
                result = fromGeoEquirectangular(longitude, latitude);
                break;
        }

        // Apply scale transform (like ScaleProjectionTransform in TerraMinusMinus)
        result[0] *= scaleX;
        result[1] *= scaleY;

        return result;
    }

    /**
     * Equirectangular projection - simplest form, matches TerraMinusMinus EquirectangularProjection
     * X = longitude * (meters per degree at equator)
     * Z = -latitude * (meters per degree)
     *
     * Note: Negative Z because in Minecraft, North is negative Z

    private double[] fromGeoEquirectangular(double longitude, double latitude) {
        // Meters per degree at equator
        double metersPerDegree = EARTH_CIRCUMFERENCE / 360.0;

        // X: East-West, scaled by cos(latitude) to account for longitude convergence at poles
        // This is the key distortion correction that TerraMinusMinus uses
        double x = longitude * metersPerDegree * Math.cos(Math.toRadians(latitude));

        // Z: North-South, inverted because Minecraft Z+ is South
        double z = -latitude * metersPerDegree;

        return new double[] { x, z };
    }

    /**
     * Web Mercator projection - matches TerraMinusMinus WebMercatorProjection
     * Used by Google Maps, OpenStreetMap, etc.

    private double[] fromGeoWebMercator(double longitude, double latitude) {
        // Web Mercator uses a spherical mercator projection
        double x = Math.toRadians(longitude);

        // Clamp latitude to avoid infinity at poles (Web Mercator limit is ~85.05113 degrees)
        double clampedLat = Math.max(-85.05113, Math.min(85.05113, latitude));
        double y = Math.log(Math.tan(Math.PI / 4.0 + Math.toRadians(clampedLat) / 2.0));

        // Scale to meters (using Earth's circumference)
        x = x * EARTH_CIRCUMFERENCE / TAU;
        y = y * EARTH_CIRCUMFERENCE / TAU;

        // Invert Y for Minecraft coordinate system
        return new double[] { x, -y };
    }

    /**
     * BTE Conformal Dymaxion projection - simplified version
     *
     * WARNING: This is a simplified approximation. The full TerraMinusMinus implementation
     * uses complex icosahedron face calculations with Newton's method for inverse transforms.
     * For accurate BTE Dymaxion projection, you would need to port the full DymaxionProjection
     * class with all its rotation matrices and face detection logic.
     *
     * This simplified version uses equirectangular with BTE's typical scale factors
     * and coordinate system orientation.

    private double[] fromGeoBteDymaxion(double longitude, double latitude) {
        // For most practical BTE purposes, the Dymaxion projection with proper scale
        // can be approximated by equirectangular with specific scale factors

        // The key BTE characteristic is the rotation and specific scaling
        double[] baseCoords = fromGeoEquirectangular(longitude, latitude);

        // Apply BTE's coordinate system rotation (swap and negate to match BTE orientation)
        // BTE Dymaxion has a specific orientation where the "cut" is through the Bering Strait
        double x = baseCoords[1];  // Swap X and Z
        double z = -baseCoords[0]; // Negate and swap

        // Note: Full implementation would require:
        // 1. Converting lat/lon to spherical coordinates
        // 2. Finding which icosahedron face the point belongs to
        // 3. Applying rotation matrix for that face
        // 4. Performing triangle transform with Newton's method iteration
        // 5. Applying BTE-specific coordinate adjustments (isEurasianPart checks, etc.)

        return new double[] { x, z };
    }

    /**
     * Converts Minecraft coordinates back to geographic coordinates.
     * Inverse of fromGeo() - needed for some operations.
     *
     * @param x Minecraft X coordinate
     * @param z Minecraft Z coordinate
     * @return double array where [0] = longitude, [1] = latitude
     * @throws OutOfProjectionBoundsException if coordinates are outside valid projection domain

    public double[] toGeo(double x, double z) throws OutOfProjectionBoundsException {
        // Remove scale first (inverse of ScaleProjectionTransform)
        x /= scaleX;
        z /= scaleY;

        switch (projectionType) {
            case WEB_MERCATOR:
                return toGeoWebMercator(x, z);
            case BTE_CONFORMAL_DYMAXION:
                return toGeoBteDymaxion(x, z);
            case EQUIRECTANGULAR:
            default:
                return toGeoEquirectangular(x, z);
        }
    }

    /**
     * Inverse equirectangular projection

    private double[] toGeoEquirectangular(double x, double z) {
        double metersPerDegree = EARTH_CIRCUMFERENCE / 360.0;

        // Inverse of fromGeoEquirectangular
        double latitude = -z / metersPerDegree;

        // X was scaled by cos(latitude), so we need to divide by it
        // But we need latitude first, so we iterate or approximate
        // For small distortions, we can approximate:
        double longitude = x / (metersPerDegree * Math.cos(Math.toRadians(latitude)));

        return new double[] { longitude, latitude };
    }

    /**
     * Inverse Web Mercator projection

    private double[] toGeoWebMercator(double x, double z) {
        double y = -z; // Undo Minecraft Z inversion

        // Convert from meters to radians
        x = x * TAU / EARTH_CIRCUMFERENCE;
        y = y * TAU / EARTH_CIRCUMFERENCE;

        double longitude = Math.toDegrees(x);
        double latitude = Math.toDegrees(2.0 * Math.atan(Math.exp(y)) - Math.PI / 2.0);

        return new double[] { longitude, latitude };
    }

    /**
     * Inverse BTE Dymaxion (simplified)

    private double[] toGeoBteDymaxion(double x, double z) {
        // Undo the swap and negate
        double baseX = -z;
        double baseZ = x;

        return toGeoEquirectangular(baseX, baseZ);
    }

    /**
     * Gets an estimation of the scale of this projection in meters per unit.
     * Matches TerraMinusMinus GeographicProjection.metersPerUnit()
     *
     * @return Meters per Minecraft block unit

    public double metersPerUnit() {
        // Average scale factor (geometric mean of X and Y scales)
        // Divided by the base meters per degree to get meters per block
        double avgScale = Math.sqrt(scaleX * scaleY);
        double baseMetersPerDegree = EARTH_CIRCUMFERENCE / 360.0;
        return baseMetersPerDegree / avgScale;
    }

    /**
     * Calculates the bounds of this projection.
     * Matches TerraMinusMinus GeographicProjection.bounds()
     *
     * @return double array [minX, minY, maxX, maxY]

    public double[] bounds() {
        try {
            double[] sw = fromGeo(-180, -90);
            double[] ne = fromGeo(180, 90);

            double minX = Math.min(sw[0], ne[0]);
            double maxX = Math.max(sw[0], ne[0]);
            double minZ = Math.min(sw[1], ne[1]);
            double maxZ = Math.max(sw[1], ne[1]);

            return new double[] { minX, minZ, maxX, maxZ };
        } catch (OutOfProjectionBoundsException e) {
            return new double[] { 0, 0, 1, 1 };
        }
    }

    /**
     * Exception thrown when coordinates are outside projection bounds.
     * Matches TerraMinusMinus OutOfProjectionBoundsException

    public static class OutOfProjectionBoundsException extends Exception {
        public OutOfProjectionBoundsException(String message) {
            super(message);
        }

        public static OutOfProjectionBoundsException get() {
            return new OutOfProjectionBoundsException("Coordinates outside projection bounds");
        }
    }

    /**
     * Utility method to convert spherical coordinates to Cartesian (from MathUtils)

    public static double[] spherical2Cartesian(double lambda, double phi) {
        double sinPhi = Math.sin(phi);
        double x = sinPhi * Math.cos(lambda);
        double y = sinPhi * Math.sin(lambda);
        double z = Math.cos(phi);
        return new double[] { x, y, z };
    }

    /**
     * Utility method to convert Cartesian to spherical coordinates (from MathUtils)

    public static double[] cartesian2Spherical(double x, double y, double z) {
        double lambda = Math.atan2(y, x);
        double phi = Math.atan2(Math.sqrt(x * x + y * y), z);
        return new double[] { lambda, phi };
    }

    /**
     * Utility method to convert geographic to spherical (from MathUtils)

    public static double[] geo2Spherical(double longitude, double latitude) {
        double lambda = Math.toRadians(longitude);
        double phi = Math.toRadians(90.0 - latitude); // Colatitude
        return new double[] { lambda, phi };
    }

    /**
     * Utility method to convert spherical to geographic (from MathUtils)

    public static double[] spherical2Geo(double lambda, double phi) {
        double longitude = Math.toDegrees(lambda);
        double latitude = 90.0 - Math.toDegrees(phi);
        return new double[] { longitude, latitude };
    }
}
*/