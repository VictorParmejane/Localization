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

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.firebase.FirebaseApp;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.GeoPoint;
import com.google.firebase.firestore.SetOptions;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class LocationService extends Service implements LocationListener {

    private static final String CHANNEL_ID = "LOCATION_CHANNEL";
    private static final int NOTIFICATION_ID = 200;
    private static final long LOOP_INTERVAL = 5000L; // 5 segundos

    private LocationManager locationManager;
    private FirebaseFirestore db;
    private Handler handler;
    private Runnable loopRunnable;

    private boolean isRodando = false;

    private String name="", cpf="", celular="", placa="", hodometro_entrada="", destino="", tipo_servico="";
    private String data_entrada="", horario_entrada="";

    @Override
    public void onCreate() {
        super.onCreate();
        FirebaseApp.initializeApp(this);
        db = FirebaseFirestore.getInstance();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        isRodando = true;
        criarNotificacao();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            name = getSafeString(intent.getStringExtra("name"));
            cpf = getSafeString(intent.getStringExtra("cpf"));
            celular = getSafeString(intent.getStringExtra("celular"));
            placa = getSafeString(intent.getStringExtra("placa"));
            hodometro_entrada = getSafeString(intent.getStringExtra("hodometro_entrada"));
            destino = getSafeString(intent.getStringExtra("destino"));
            tipo_servico = getSafeString(intent.getStringExtra("tipo_servico"));
            data_entrada = getSafeString(intent.getStringExtra("data_entrada"));
            horario_entrada = getSafeString(intent.getStringExtra("horario_entrada"));

            if(data_entrada.isEmpty()){
                Date now = new Date();
                data_entrada = new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(now);
                horario_entrada = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(now);
            }
        }

        // Garante que o registro existe no Firestore imediatamente
        enviarParaFirestore(0, 0);

        iniciarListenerGps();
        iniciarLoop();
        return START_STICKY;
    }

    private String getSafeString(String val) { return val == null ? "" : val; }

    private void criarNotificacao() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel chan = new NotificationChannel(CHANNEL_ID, "Frota", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(chan);
        }
        Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Monitoramento Ativo")
                .setContentText("Placa: " + placa)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, notif);
    }

    private void iniciarListenerGps() {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
            // Solicita GPS (mais preciso)
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 1f, this, Looper.getMainLooper());
            // Solicita Rede (backup)
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000, 5f, this, Looper.getMainLooper());
        } catch (Exception e) { Log.e("LocationService", "Erro GPS: " + e.getMessage()); }
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location != null) enviarParaFirestore(location.getLatitude(), location.getLongitude());
    }

    @Override public void onStatusChanged(String p, int s, Bundle e) {}
    @Override public void onProviderEnabled(String p) {}
    @Override public void onProviderDisabled(String p) {}

    private void iniciarLoop() {
        handler = new Handler(Looper.getMainLooper());
        loopRunnable = new Runnable() {
            @Override
            public void run() {
                if (isRodando) {
                    try {
                        if (ActivityCompat.checkSelfPermission(LocationService.this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                            Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                            if (last == null) last = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);

                            // Se tiver localização, atualiza. Se não tiver (lat 0, lon 0), atualiza também para manter heartbeat
                            if (last != null) {
                                enviarParaFirestore(last.getLatitude(), last.getLongitude());
                            } else {
                                enviarParaFirestore(0, 0);
                            }
                        }
                    } catch (Exception e) { }
                    handler.postDelayed(this, LOOP_INTERVAL);
                }
            }
        };
        handler.post(loopRunnable);
    }

    private void enviarParaFirestore(double lat, double lon) {
        if (!isRodando || placa.isEmpty()) return;

        try {
            // ID Padronizado da Placa
            String docId = placa.toUpperCase().replace("-", "").trim();
            GeoPoint geo = new GeoPoint(lat, lon);

            Map<String, Object> dados = new HashMap<>();
            dados.put("placa", placa);
            dados.put("cpf", cpf);
            dados.put("name", name);
            dados.put("location", geo);
            dados.put("lastupdate", Timestamp.now());

            // Apenas atualiza esses campos, mantendo o resto (SetOptions.merge)
            db.collection("ambulances").document(docId).set(dados, SetOptions.merge());

        } catch (Exception e) { Log.e("Firestore", "Erro: " + e.getMessage()); }
    }

    @Override
    public void onDestroy() {
        isRodando = false;
        super.onDestroy();
        if (locationManager != null) locationManager.removeUpdates(this);
        if (handler != null) handler.removeCallbacks(loopRunnable);
        stopForeground(true);
        // NOTA: O serviço não apaga o documento do Firestore.
        // Quem apaga é a MainActivity no método processarFimViagem().
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}