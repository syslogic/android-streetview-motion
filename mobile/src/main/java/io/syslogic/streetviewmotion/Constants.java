package io.syslogic.streetviewmotion;

/**
 * Common Constants
 * @author Martin Zeitler
 * @version 1.0.1
 */
public class Constants {
    public static final String PREFERENCE_KEY_LATITUDE  = "latitude";
    public static final String PREFERENCE_KEY_LONGITUDE = "longitude";

    public static final float PREFERENCE_DEFAULT_LATITUDE = 48.1429469F;
    public static final float PREFERENCE_DEFAULT_LONGITUDE = 11.5800361F;

    /** Minimum time interval between location updates, in milliseconds. */
    public static final int LOCATION_MANAGER_REFRESH_INTERVAL = 1000;

    /** Minimum distance between location updates, in meters. */
    public static final int LOCATION_MANAGER_REFRESH_DISTANCE = 10;
}
