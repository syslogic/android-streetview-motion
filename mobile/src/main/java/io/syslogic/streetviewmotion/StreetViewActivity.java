package io.syslogic.streetviewmotion;

import android.Manifest;
import android.content.Context;
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

/**
 * StreetView {@link FragmentActivity}
 * @author Martin Zeitler
 * @version 1.0.0
**/
public class StreetViewActivity extends FragmentActivity implements LocationListener, SensorEventListener, StreetViewPanorama.OnStreetViewPanoramaChangeListener, StreetViewPanorama.OnStreetViewPanoramaCameraChangeListener, StreetViewPanorama.OnStreetViewPanoramaClickListener, StreetViewPanorama.OnStreetViewPanoramaLongClickListener {

    /** {@link Log} Tag */
    private static final String LOG_TAG = StreetViewActivity.class.getSimpleName();

    /** Debug Output */
    protected static final boolean mDebug = BuildConfig.DEBUG;

    /** resIds */
    private final int resId = R.layout.fragment_streetview;
    private final int resIdLayout = R.id.streetviewpanorama;

    private StreetViewPanorama panorama = null;
    private LocationManager mLocationManager = null;
    private SensorManager mSensorManager = null;
    private Sensor mSensor;

    private final float[] mAccelerometerReading = new float[3];
    private final float[] mMagnetometerReading = new float[3];
    private final float[] mOrientationAngles = new float[3];
    private final float[] mRotationMatrix = new float[9];

    private LatLng currentLocation = new LatLng(48.1429469,11.5800361);
    private StreetViewPanoramaOrientation currentOrientation;
    private float currentZoom    = 0.0f;
    private float currentBearing = 0;
    private float currentTilt    = 30;

    private int mMagneticFieldAccuracy = 0;
    private int mGravityAccuracy = 0;

    private final int LOCATION_REFRESH_TIME = 1000;
    private final int LOCATION_REFRESH_DISTANCE = 10;


    @Override
    protected void onCreate(final Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        this.setContentView(this.resId);

        /* Street-View */
        SupportStreetViewPanoramaFragment fragment = (SupportStreetViewPanoramaFragment) getSupportFragmentManager().findFragmentById(this.resIdLayout);
        assert fragment != null;

        fragment.getStreetViewPanoramaAsync(streetViewPanorama -> { // OnStreetViewPanoramaReadyCallback

            this.panorama = streetViewPanorama;

            // this.panorama.setUserNavigationEnabled(false);
            // this.panorama.setPanningGesturesEnabled(false);

            this.panorama.setOnStreetViewPanoramaCameraChangeListener(StreetViewActivity.this);
            this.panorama.setOnStreetViewPanoramaCameraChangeListener(StreetViewActivity.this);
            this.panorama.setOnStreetViewPanoramaLongClickListener(StreetViewActivity.this);
            this.panorama.setOnStreetViewPanoramaClickListener(StreetViewActivity.this);

            /* Set the panorama to the default location upon startup */
            if (savedInstanceState == null) {
                this.panorama.setPosition(this.currentLocation, StreetViewSource.DEFAULT);
            }
        });

        /* GPS */
        this.mLocationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (this.mLocationManager != null) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 200);
            } else {
                this.requestLocationUpdates();
            }
        }

        /* Sensors */
        this.mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (this.mSensorManager != null) {
            int sensorType = Sensor.TYPE_GAME_ROTATION_VECTOR;
            this.mSensor = this.mSensorManager.getDefaultSensor(sensorType);
            this.mSensorManager.registerListener(this, this.mSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if(this.mSensorManager != null) {
            this.mSensorManager.unregisterListener(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Sensor accelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accelerometer != null) {
            this.mSensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL, SensorManager.SENSOR_DELAY_UI);
        }
        Sensor magneticField = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        if (magneticField != null) {
            this.mSensorManager.registerListener(this, magneticField, SensorManager.SENSOR_DELAY_NORMAL, SensorManager.SENSOR_DELAY_UI);
        }
    }

    /** Sensor Changed */
    @Override
    public void onSensorChanged(SensorEvent event) {

        switch (event.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                System.arraycopy(event.values, 0, mAccelerometerReading, 0, mAccelerometerReading.length);
                break;

            case Sensor.TYPE_MAGNETIC_FIELD:
                System.arraycopy(event.values, 0, mMagnetometerReading, 0, mMagnetometerReading.length);
                break;
        }

        // Compute the three orientation angles based on the most recent readings from the device's accelerometer and magnetometer.

        // Update rotation matrix, which is needed to update orientation angles.
        SensorManager.getRotationMatrix(this.mRotationMatrix, null, this.mAccelerometerReading, this.mMagnetometerReading);

        // "mRotationMatrix" now has up-to-date information.
        SensorManager.getOrientation(this.mRotationMatrix, this.mOrientationAngles);

        // "mOrientationAngles" now has up-to-date information.

        /* in case the view had not yet been initialized. */
        if (this.panorama != null) {

            float x = 0.0f;
            float y = 0.0f;
            float z = 0.0f;
            switch (event.sensor.getType()) {

                case Sensor.TYPE_ACCELEROMETER:
                    x = this.mAccelerometerReading[0];
                    y = this.mAccelerometerReading[1];
                    z = this.mAccelerometerReading[2];
                    break;

                case Sensor.TYPE_MAGNETIC_FIELD:
                    x = this.mMagnetometerReading[0];
                    y = this.mMagnetometerReading[1];
                    z = this.mMagnetometerReading[2];
                    break;
            }

            this.currentOrientation = this.panorama.getPanoramaCamera().getOrientation();
            Point point = this.panorama.orientationToPoint(this.currentOrientation);
            if (point != null) {

                /* the tilt needs to be in between -90 and 90 inclusive */
                StreetViewPanoramaCamera camera = this.panorama.getPanoramaCamera();
                if((camera.tilt + y) >= -90 && (camera.tilt + y) <= 90) {

                    /* no clue why bearing-x & tilt+y, but it works */
                    this.currentBearing = camera.bearing -x; // pitch
                    this.currentTilt = camera.tilt + y; // roll
                    this.currentZoom = camera.zoom;

                    this.panorama.animateTo(new StreetViewPanoramaCamera
                        .Builder()
                        .orientation(this.currentOrientation)
                        .zoom(this.currentZoom)
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
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        int SensorType = sensor.getType();
        switch(SensorType) {
            case Sensor.TYPE_GRAVITY: mGravityAccuracy = accuracy; break;
            case Sensor.TYPE_MAGNETIC_FIELD: mMagneticFieldAccuracy = accuracy; break;
        }
    }

    /** GPS onLocationChanged() */
    @Override
    public void onLocationChanged(Location location) {
        if(mDebug) {Log.d(LOG_TAG, "onLocationChanged(" + location.getLatitude() + ", " + location.getLongitude() + ")");}
        this.currentLocation = new LatLng(location.getLatitude(), location.getLongitude());

        // The problem is that not every GPS location does have a street-view available.
        // if (this.panorama != null) {this.panorama.setPosition(this.currentLocation);}
    }

    /**
     * GPS: This callback will never be invoked and providers can be
     * considered as always in the {@link LocationProvider#AVAILABLE} state.
     */
    @Override
    @SuppressWarnings("deprecation")
    public void onStatusChanged(String provider, int status, Bundle extras) {}

    /** GPS: When the sensor had been enabled. */
    @Override
    public void onProviderEnabled(String provider) {
        if(mDebug) {Log.d(LOG_TAG, provider + ".onProviderEnabled()");}
        this.requestLocationUpdates();
    }

    /** GPS: When the sensor had been disabled. */
    @Override
    public void onProviderDisabled(String provider) {
        if(mDebug) {Log.d(LOG_TAG, provider + ".onProviderDisabled()");}
        this.mLocationManager.removeUpdates(this);
    }

    /** start: StreetViewPanorama */
    @Override
    public void onStreetViewPanoramaChange(StreetViewPanoramaLocation location) {
        this.currentLocation = location.position;
    }

    @Override
    public void onStreetViewPanoramaCameraChange(StreetViewPanoramaCamera camera) {
        this.currentOrientation = camera.getOrientation();
    }

    @Override
    public void onStreetViewPanoramaClick(@NonNull StreetViewPanoramaOrientation orientation) {
        float zoomLevel = this.panorama.getPanoramaCamera().zoom;
        if(zoomLevel < 2.0f) {zoomLevel += 0.5f;} else {zoomLevel = 0.0f;}
        this.updateZoom(orientation, zoomLevel);
    }

    /** toggles the zoom-level */
    @Override
    public void onStreetViewPanoramaLongClick(@NonNull StreetViewPanoramaOrientation orientation) {
        float zoomLevel = this.panorama.getPanoramaCamera().zoom;
        if(zoomLevel < 2.0f) {zoomLevel += 0.5f;} else {zoomLevel = 0.0f;}
        this.updateZoom(orientation, zoomLevel);
    }


    /** Permissions */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case 200:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    requestLocationUpdates();
                } else if (mDebug) {
                    Toast.makeText(StreetViewActivity.this, "Permission was denied", Toast.LENGTH_LONG).show();
                }
                break;
        }
    }

    private void updateZoom(StreetViewPanoramaOrientation orientation, float zoomLevel) {
        this.currentBearing = this.panorama.getPanoramaCamera().bearing;
        this.currentTilt = this.panorama.getPanoramaCamera().tilt;
        Point point = this.panorama.orientationToPoint(orientation);
        if (point != null) {
            this.panorama.animateTo(
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

    private void requestLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            this.mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, LOCATION_REFRESH_TIME, LOCATION_REFRESH_DISTANCE, this);
        }
    }
}
