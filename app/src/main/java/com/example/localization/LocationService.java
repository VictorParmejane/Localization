package com.example.localization;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.firebase.FirebaseApp;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.GeoPoint;

import java.util.HashMap;
import java.util.Map;

public class LocationService extends Service implements LocationListener {

    private static final String CHANNEL_ID = "LOCATION_CHANNEL";
    private static final int NOTIFICATION_ID = 200;
    private static final long LOOP_INTERVAL = 5000L;

    private LocationManager locationManager;
    private FirebaseFirestore db;
    private Handler handler;
    private Runnable loopRunnable;
    private String userName = "Desconhecido";

    @Override
    public void onCreate() {
        super.onCreate();
        FirebaseApp.initializeApp(this);
        db = FirebaseFirestore.getInstance();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        criarNotificacao();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("user_name")) {
            userName = intent.getStringExtra("user_name");
        }

        iniciarListenerGps();
        iniciarLoop();

        Toast.makeText(this, "📍 Rastreamento iniciado para " + userName, Toast.LENGTH_SHORT).show();
        return START_STICKY;
    }

    private void criarNotificacao() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel chan = new NotificationChannel(CHANNEL_ID,
                    "Rastreamento de Localização",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(chan);
        }

        Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Rastreamento ativo")
                .setContentText("Enviando coordenadas para o servidor…")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, notif);
    }

    private void iniciarListenerGps() {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                            != PackageManager.PERMISSION_GRANTED) {
                stopSelf();
                return;
            }

            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000,
                    1f,
                    this,
                    Looper.getMainLooper()
            );

            locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    2000,
                    5f,
                    this,
                    Looper.getMainLooper()
            );
        } catch (Exception e) {
            Log.e("LocationService", "Erro iniciarListenerGps: " + e.getMessage());
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location != null) {
            enviarParaFirestore(location.getLatitude(), location.getLongitude());
        }
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}

    private void iniciarLoop() {
        handler = new Handler(Looper.getMainLooper());
        loopRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    if (ActivityCompat.checkSelfPermission(LocationService.this,
                            Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                            ActivityCompat.checkSelfPermission(LocationService.this,
                                    Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                        if (last == null)
                            last = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                        if (last != null)
                            enviarParaFirestore(last.getLatitude(), last.getLongitude());
                    }
                } catch (Exception e) {
                    Log.e("Loop", "Erro: " + e.getMessage());
                }
                handler.postDelayed(this, LOOP_INTERVAL);
            }
        };
        handler.post(loopRunnable);
    }

    private void enviarParaFirestore(double lat, double lon) {
        try {
            String docId = Build.MODEL + "_" + Build.ID;
            GeoPoint geo = new GeoPoint(lat, lon);
            Timestamp timestamp = Timestamp.now();

            Map<String, Object> dados = new HashMap<>();
            dados.put("name", userName);
            dados.put("location", geo);
            dados.put("lastupdate", timestamp);

            db.collection("ambulances")
                    .document(docId)
                    .set(dados)
                    .addOnSuccessListener(a -> Log.i("Firestore", "Atualizado com sucesso"))
                    .addOnFailureListener(e -> Log.e("Firestore", "Erro ao enviar: " + e.getMessage()));
        } catch (Exception e) {
            Log.e("Firestore", "Exceção: " + e.getMessage());
        }
    }

    private void excluirDocumento() {
        String docId = Build.MODEL + "_" + Build.ID;
        db.collection("ambulances").document(docId)
                .delete()
                .addOnSuccessListener(a -> Log.i("Firestore", "Documento removido"))
                .addOnFailureListener(e -> Log.e("Firestore", "Erro exclusão: " + e.getMessage()));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (locationManager != null) locationManager.removeUpdates(this);
        if (handler != null && loopRunnable != null) handler.removeCallbacks(loopRunnable);
        excluirDocumento();
        stopForeground(true);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}