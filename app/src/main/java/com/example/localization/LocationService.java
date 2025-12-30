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
import android.os.Bundle; // <--- ADICIONADO PARA CORRIGIR O ERRO DO BUNDLE (caso precise)
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull; // Importante para o onLocationChanged
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.firebase.FirebaseApp;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.GeoPoint;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.Map;

public class LocationService extends Service implements LocationListener {

    private static final String CHANNEL_ID = "FROTA_TRACKER_CHANNEL";
    private static final int NOTIFICATION_ID = 300;

    // Intervalo de envio para o banco (10 segundos)
    private static final long DB_UPDATE_INTERVAL = 10000L;

    private LocationManager locationManager;
    private FirebaseFirestore db;
    private Handler handler;
    private Runnable loopRunnable;

    private boolean isRodando = false;
    private String placa = "";
    private String motorista = "";
    private Location lastLocation = null;

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
        if (intent != null) {
            placa = intent.getStringExtra("placa");
            motorista = intent.getStringExtra("motorista");
        }

        // Se a placa estiver vazia, tenta recuperar ou evita erro
        if (placa == null) placa = "DESCONHECIDO";

        isRodando = true;
        iniciarListenerGps();
        iniciarLoopEnvio();

        atualizarNotificacao("Monitorando Placa: " + placa);

        return START_STICKY;
    }

    private void criarNotificacao() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel chan = new NotificationChannel(CHANNEL_ID, "Serviço de Rastreamento", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(chan);
        }
        startForeground(NOTIFICATION_ID, buildNotification("Aguardando GPS..."));
    }

    private void atualizarNotificacao(String texto) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, buildNotification(texto));
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Rastreador de Frota")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build();
    }

    private void iniciarListenerGps() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;

        // Listener GPS
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 5f, this, Looper.getMainLooper());
        // Listener Rede (Backup)
        locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000, 10f, this, Looper.getMainLooper());
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        // Guardamos a localização mais recente na memória
        this.lastLocation = location;
        Log.d("LocationService", "Nova coordenada recebida no listener");
    }

    // --- CORREÇÃO AQUI ---
    // Removi os métodos onStatusChanged, onProviderEnabled e onProviderDisabled.
    // Nas versões novas do Android SDK, eles têm implementação padrão na interface,
    // então você não precisa declará-los se estiverem vazios.
    // Isso resolve o erro "Method does not override".

    private void iniciarLoopEnvio() {
        handler = new Handler(Looper.getMainLooper());
        loopRunnable = new Runnable() {
            @Override
            public void run() {
                if (isRodando && lastLocation != null && !placa.isEmpty()) {
                    enviarParaFirestore(lastLocation);
                }
                handler.postDelayed(this, DB_UPDATE_INTERVAL);
            }
        };
        handler.post(loopRunnable);
    }

    private void enviarParaFirestore(Location loc) {
        try {
            String docId = placa.toUpperCase().replace("-", "").trim();
            GeoPoint geo = new GeoPoint(loc.getLatitude(), loc.getLongitude());

            Map<String, Object> dados = new HashMap<>();
            dados.put("location", geo);
            dados.put("bearing", loc.getBearing());
            dados.put("speed", loc.getSpeed() * 3.6);
            dados.put("motorista_atual", motorista);
            dados.put("lastupdate", Timestamp.now());
            dados.put("status", "online");

            db.collection("ambulances").document(docId).set(dados, SetOptions.merge());

        } catch (Exception e) { Log.e("Firestore", "Erro: " + e.getMessage()); }
    }

    @Override
    public void onDestroy() {
        isRodando = false;
        if (locationManager != null) locationManager.removeUpdates(this);
        if (handler != null) handler.removeCallbacks(loopRunnable);
        stopForeground(true);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}