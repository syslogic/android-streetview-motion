package io.syslogic.streetviewmotion;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.net.ConnectivityManager;
import android.util.Log;
import android.widget.Toast;
import android.os.Bundle;

import com.google.android.gms.maps.StreetViewPanorama;
import com.google.android.gms.maps.SupportStreetViewPanoramaFragment;
import com.google.android.gms.maps.model.StreetViewPanoramaCamera;
import com.google.android.gms.maps.model.StreetViewPanoramaLocation;
import com.google.android.gms.maps.model.StreetViewPanoramaOrientation;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.StreetViewSource;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.PreferenceManager;

/**
 * StreetView {@link FragmentActivity}
 * @author Martin Zeitler
 */
public class StreetViewActivity extends FragmentActivity
        implements LocationListener, SensorEventListener,
        StreetViewPanorama.OnStreetViewPanoramaChangeListener,
        StreetViewPanorama.OnStreetViewPanoramaCameraChangeListener,
        StreetViewPanorama.OnStreetViewPanoramaClickListener,
        StreetViewPanorama.OnStreetViewPanoramaLongClickListener {

    /** {@link Log} Tag */
    private static final String LOG_TAG = StreetViewActivity.class.getSimpleName();

    /** Debug Output */
    protected static final boolean mDebug = BuildConfig.DEBUG;

    /** Shared Preferences */
    private SharedPreferences prefs = null;
    private LocationManager mLocationManager = null;
    private SensorManager mSensorManager = null;

    /** @noinspection MismatchedReadAndWriteOfArray */
    private final float[] mRotationReading = new float[3];
    private final float[] mAccelerometerReading = new float[3];
    private final float[] mMagnetometerReading = new float[3];
    private final float[] mOrientationAngles = new float[3];
    private final float[] mRotationMatrix = new float[9];

    private StreetViewPanorama mPanorama = null;
    private StreetViewPanoramaOrientation currentOrientation;
    private LatLng currentLocation;
    private float currentBearing = 0;
    private float currentTilt    = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.setContentView(R.layout.fragment_street_view);
        this.prefs = PreferenceManager.getDefaultSharedPreferences(this);

        /* Initial Location */
        this.currentLocation = new LatLng(
                this.prefs.getFloat(Constants.PREFERENCE_KEY_LATITUDE, Constants.PREFERENCE_DEFAULT_LATITUDE),
                this.prefs.getFloat(Constants.PREFERENCE_KEY_LONGITUDE, Constants.PREFERENCE_DEFAULT_LONGITUDE)
        );

        /* StreetView Panorama Fragment */
        SupportStreetViewPanoramaFragment fragment = getStreetViewPanoramaFragment();
        if (fragment != null && this.mPanorama == null) {
            fragment.getStreetViewPanoramaAsync(streetViewPanorama -> { // OnStreetViewPanoramaReadyCallback

                this.mPanorama = streetViewPanorama;
                // this.mPanorama.setUserNavigationEnabled(false);
                // this.mPanorama.setPanningGesturesEnabled(false);
                this.mPanorama.setOnStreetViewPanoramaCameraChangeListener(StreetViewActivity.this);
                this.mPanorama.setOnStreetViewPanoramaCameraChangeListener(StreetViewActivity.this);
                this.mPanorama.setOnStreetViewPanoramaLongClickListener(StreetViewActivity.this);
                this.mPanorama.setOnStreetViewPanoramaClickListener(StreetViewActivity.this);

                /* Set the panorama to the default location upon startup */
                if (savedInstanceState == null) {
                    this.mPanorama.setPosition(this.currentLocation, StreetViewSource.DEFAULT);
                }
            });
        }

        /* GPS */
        this.mLocationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (this.mLocationManager != null) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 200);
            } else {
                this.requestLocationUpdates();
            }
        }

        /* Network */
        if (! this.isConnected()) {
            Toast.makeText(StreetViewActivity.this, "A network connection is required", Toast.LENGTH_LONG).show();
        }
    }

    @SuppressLint({"deprecation", "MissingPermission"})
    @SuppressWarnings({"deprecation", "RedundantSuppression"})
    boolean isConnected() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        android.net.NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if(this.mSensorManager != null) {
            this.mSensorManager.unregisterListener(this);
        }
    }

    /** Registering the listeners for the Sensors */
    @Override
    protected void onResume() {
        super.onResume();
        this.mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (this.mSensorManager != null) {

            Sensor mRotationSensor = this.mSensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
            this.mSensorManager.registerListener(this, mRotationSensor, SensorManager.SENSOR_DELAY_UI);

            Sensor accelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accelerometer != null) {
                this.mSensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL, SensorManager.SENSOR_DELAY_UI);
            }

            Sensor magneticField = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            if (magneticField != null) {
                this.mSensorManager.registerListener(this, magneticField, SensorManager.SENSOR_DELAY_NORMAL, SensorManager.SENSOR_DELAY_UI);
            }
        }
    }

    /** Sensor Changed
     * @noinspection CommentedOutCode*/
    @Override
    public void onSensorChanged(@NonNull SensorEvent event) {

        switch (event.sensor.getType()) {
            case Sensor.TYPE_GAME_ROTATION_VECTOR ->
                    System.arraycopy(event.values, 0, mRotationReading, 0, mRotationReading.length);
            case Sensor.TYPE_ACCELEROMETER ->
                    System.arraycopy(event.values, 0, mAccelerometerReading, 0, mAccelerometerReading.length);
            case Sensor.TYPE_MAGNETIC_FIELD ->
                    System.arraycopy(event.values, 0, mMagnetometerReading, 0, mMagnetometerReading.length);
        }

        // Compute the three orientation angles based on the most recent readings from the device's accelerometer and magnetometer.

        // Update rotation matrix, which is needed to update orientation angles.
        SensorManager.getRotationMatrix(this.mRotationMatrix, null, this.mAccelerometerReading, this.mMagnetometerReading);

        // "mRotationMatrix" now has up-to-date information.
        SensorManager.getOrientation(this.mRotationMatrix, this.mOrientationAngles);

        // "mOrientationAngles" now has up-to-date information.

        /* in case the view had not yet been initialized. */
        if (this.mPanorama != null) {

            float x = 0.0f;
            float y = 0.0f;
            // float z = 0.0f;
            switch (event.sensor.getType()) {

                case Sensor.TYPE_GAME_ROTATION_VECTOR:
                    // x = this.mRotationReading[0];
                    // y = this.mRotationReading[1];
                    // z = this.mRotationReading[2];
                    break;

                case Sensor.TYPE_ACCELEROMETER:
                    x = this.mAccelerometerReading[0];
                    y = this.mAccelerometerReading[1];
                    // z = this.mAccelerometerReading[2];
                    break;

                case Sensor.TYPE_MAGNETIC_FIELD:
                    // x = this.mMagnetometerReading[0];
                    // y = this.mMagnetometerReading[1];
                    // z = this.mMagnetometerReading[2];
                    break;
            }

            this.currentOrientation = this.mPanorama.getPanoramaCamera().getOrientation();
            Point point = this.mPanorama.orientationToPoint(this.currentOrientation);
            if (point != null) {

                /* the tilt needs to be in between -90 and 90 inclusive */
                StreetViewPanoramaCamera camera = this.mPanorama.getPanoramaCamera();
                if((camera.tilt + y) >= -90 && (camera.tilt + y) <= 90) {

                    /* no clue why bearing-x & tilt+y, but it works */
                    this.currentBearing = camera.bearing -x; // pitch
                    this.currentTilt = camera.tilt + y; // roll
                    float currentZoom = camera.zoom;

                    this.mPanorama.animateTo(new StreetViewPanoramaCamera
                        .Builder()
                        .orientation(this.currentOrientation)
                        .zoom(currentZoom)
                        .bearing(this.currentBearing)
                        .tilt(this.currentTilt)
                        .build(),0
                    );
                }
            }
        }
    }

    /** Sensor Accuracy Changed */
    @Override
    public void onAccuracyChanged(@NonNull Sensor sensor, int accuracy) {
        int SensorType = sensor.getType();
        switch(SensorType) {
            case Sensor.TYPE_GRAVITY:
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                break;
        }
    }

    /** GPS onLocationChanged() */
    @Override
    public void onLocationChanged(@NonNull Location location) {
        if (mDebug) {Log.d(LOG_TAG, "onLocationChanged() -> " + location.getLatitude() + ", " + location.getLongitude());}
        this.prefs.edit()
                .putFloat(Constants.PREFERENCE_KEY_LATITUDE, (float) location.getLatitude())
                .putFloat(Constants.PREFERENCE_KEY_LONGITUDE, (float) location.getLongitude())
                .apply();

        // The problem is that not every GPS location does have a street-view available.
        this.currentLocation = new LatLng(location.getLatitude(), location.getLongitude());
        if (this.mPanorama != null) {this.mPanorama.setPosition(this.currentLocation);}
    }

    /**
     * GPS: This callback will never be invoked and providers can be
     * considered as always in the {@link LocationProvider#AVAILABLE} state.
     */
    @Override
    @SuppressWarnings({"deprecation", "RedundantSuppression"})
    public void onStatusChanged(String provider, int status, Bundle extras) {}

    /** GPS: When the sensor had been enabled. */
    @Override
    public void onProviderEnabled(@NonNull String provider) {
        if (mDebug) {
            Toast.makeText(StreetViewActivity.this, provider.toUpperCase() + " enabled.", Toast.LENGTH_SHORT).show();
            Log.d(LOG_TAG, provider + ".onProviderEnabled(\"" + provider.toUpperCase() + "\")");
        }
        this.requestLocationUpdates();
    }

    /** GPS: When the sensor had been disabled. */
    @Override
    @SuppressLint("MissingPermission")
    public void onProviderDisabled(@NonNull String provider) {
        if (mDebug) {
            Toast.makeText(StreetViewActivity.this, provider.toUpperCase() + " disabled.", Toast.LENGTH_SHORT).show();
            Log.d(LOG_TAG, provider + ".onProviderDisabled(\"" + provider.toUpperCase() + "\")");
        }
        this.mLocationManager.removeUpdates(this);
    }

    /** start: StreetViewPanorama */
    @Override
    public void onStreetViewPanoramaChange(@NonNull StreetViewPanoramaLocation location) {
        this.currentLocation = location.position;
    }

    @Override
    public void onStreetViewPanoramaCameraChange(@NonNull StreetViewPanoramaCamera camera) {
        this.currentOrientation = camera.getOrientation();
    }

    @Override
    public void onStreetViewPanoramaClick(@NonNull StreetViewPanoramaOrientation orientation) {
        float zoomLevel = this.mPanorama.getPanoramaCamera().zoom;
        if(zoomLevel < 2.0f) {zoomLevel += 0.5f;} else {zoomLevel = 0.0f;}
        this.updateZoom(orientation, zoomLevel);
    }

    /** Toggles the zoom-level */
    @Override
    public void onStreetViewPanoramaLongClick(@NonNull StreetViewPanoramaOrientation orientation) {
        float zoomLevel = this.mPanorama.getPanoramaCamera().zoom;
        if(zoomLevel < 2.0f) {zoomLevel += 0.5f;} else {zoomLevel = 0.0f;}
        this.updateZoom(orientation, zoomLevel);
    }

    /** Request Permissions Result */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 200) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                requestLocationUpdates();
            } else if (mDebug) {
                Toast.makeText(StreetViewActivity.this, "Permission was denied", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void updateZoom(StreetViewPanoramaOrientation orientation, float zoomLevel) {
        this.currentBearing = this.mPanorama.getPanoramaCamera().bearing;
        this.currentTilt = this.mPanorama.getPanoramaCamera().tilt;
        Point point = this.mPanorama.orientationToPoint(orientation);
        if (point != null) {
            this.mPanorama.animateTo(
                new StreetViewPanoramaCamera.Builder()
                    .orientation(orientation)
                    .bearing(this.currentBearing)
                    .tilt(this.currentTilt)
                    .zoom(zoomLevel)
                    .build(),
                    1000
            );
        }
    }

    private SupportStreetViewPanoramaFragment getStreetViewPanoramaFragment() {
        return (SupportStreetViewPanoramaFragment) getSupportFragmentManager().findFragmentById(R.id.street_view_panorama);
    }

    private boolean hasCoarseLocationPermission() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasFineLocationPermission() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    private void requestLocationUpdates() {
        if (this.hasCoarseLocationPermission() && this.hasFineLocationPermission()) {
            this.mLocationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    Constants.LOCATION_MANAGER_REFRESH_INTERVAL,
                    Constants.LOCATION_MANAGER_REFRESH_DISTANCE,
                    this
            );
        }
    }
}
