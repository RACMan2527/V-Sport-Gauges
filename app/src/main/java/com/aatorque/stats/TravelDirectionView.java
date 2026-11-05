package com.aatorque.stats;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.AttributeSet;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.content.ContextCompat;

/** Displays GPS course over ground, not the handset's compass orientation. */
public class TravelDirectionView extends AppCompatTextView implements LocationListener {
    private LocationManager locationManager;
    private boolean listening;

    public TravelDirectionView(Context context, AttributeSet attrs) {
        super(context, attrs);
        SharedPreferences prefs = context.getSharedPreferences("direction_settings", Context.MODE_PRIVATE);
        String lastDirection = prefs.getString("last_direction", "--");
        setText(" • " + lastDirection);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        locationManager = (LocationManager) getContext().getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null ||
            ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 3f, this);
            listening = true;
        } catch (SecurityException | IllegalArgumentException ignored) {
            listening = false;
        }
    }

    @Override protected void onDetachedFromWindow() {
        if (listening && locationManager != null) {
            locationManager.removeUpdates(this);
            listening = false;
        }
        super.onDetachedFromWindow();
    }

    @Override public void onLocationChanged(Location location) {
        // Course is meaningful only while moving; retain last valid direction at stops.
        if (!location.hasBearing() || !location.hasSpeed() || location.getSpeed() < 2.0f) return;
        String[] directions = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        int sector = Math.round((location.getBearing() % 360f) / 45f) % 8;
        String direction = directions[sector];
        setText(" • " + direction);
        getContext().getSharedPreferences("direction_settings", Context.MODE_PRIVATE)
            .edit().putString("last_direction", direction).apply();
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}
}
