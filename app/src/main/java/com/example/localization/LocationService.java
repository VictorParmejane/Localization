package com.example.localization;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONObject;

public class LocationService extends Service implements LocationListener {

    // 🔴 CONFIRA SEU IP DO NGROK OU REDE LOCAL AQUI
    private static final String BASE_URL = "https://vorant-unindulgently-miracle.ngrok-free.dev";

    private static final String CHANNEL_ID = "frota_channel_01";
    private static final int NOTIFICATION_ID = 999;

    private LocationManager locationManager;
    private RequestQueue requestQueue;
    private String placa = "INICIANDO...";

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        requestQueue = Volley.newRequestQueue(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("placa")) {
            String p = intent.getStringExtra("placa");
            if (p != null && !p.isEmpty()) {
                placa = p;
            }
        }

        if (placa == null || placa.equals("INICIANDO...") || placa.isEmpty()) {
            SharedPreferences prefs = getSharedPreferences("DadosViagem", Context.MODE_PRIVATE);
            String placaSalva = prefs.getString("placa_ativa", "");
            if (!placaSalva.isEmpty()) {
                placa = placaSalva;
            }
        }

        startForegroundServiceCompat();
        iniciarListenerGps();

        if (!placa.equals("INICIANDO...")) {
            enviarStatus("online");
        }

        return START_STICKY;
    }

    private void startForegroundServiceCompat() {
        criarCanalNotificacao();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Rastreamento Ativo")
                .setContentText("Veículo: " + placa)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void iniciarListenerGps() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            // ALTA PRECISÃO: Atualiza a cada 1000ms (1 segundo) ou 1 metro de distância
            // Isso cria mais pontos para o filtro do servidor trabalhar melhor
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 1f, this);

            // BACKUP: Se GPS falhar, usa rede (menos preciso, tempo maior)
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000, 10f, this);
        } catch (Exception e) {
            Log.e("GPS", "Erro: " + e.getMessage());
        }
    }

    @Override
    public void onLocationChanged(@NonNull Location loc) {
        if (placa.equals("INICIANDO...")) return;

        // --- FILTRO DE PRECISÃO ---
        // Se a margem de erro for maior que 30 metros, descarta o ponto.
        // Isso evita "pontos malucos" que sujam o KM.
        if (loc.hasAccuracy() && loc.getAccuracy() > 30) {
            Log.d("GPS_FILTER", "Ignorado: Precisão ruim (" + loc.getAccuracy() + "m)");
            return;
        }

        String url = BASE_URL + "/api/localizacao";
        JSONObject json = new JSONObject();
        try {
            json.put("placa", placa);
            json.put("latitude", loc.getLatitude());
            json.put("longitude", loc.getLongitude());

            // Filtro de velocidade muito baixa para evitar ruído quando parado
            double vel = loc.getSpeed() * 3.6;
            if (vel < 1.0) vel = 0;
            json.put("velocidade", vel);

        } catch (Exception e) {}

        JsonObjectRequest req = new JsonObjectRequest(Request.Method.POST, url, json, null,
                error -> Log.e("Volley", "Erro envio: " + error.toString()));

        req.setRetryPolicy(new DefaultRetryPolicy(5000, 2, 1.5f));
        requestQueue.add(req);
    }

    private void enviarStatus(String status) {
        String url = BASE_URL + "/api/status";
        JSONObject json = new JSONObject();
        try {
            json.put("placa", placa);
            json.put("status", status);
        } catch (Exception e) {}
        requestQueue.add(new JsonObjectRequest(Request.Method.POST, url, json, null, null));
    }

    private void criarCanalNotificacao() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Serviço de Frota", NotificationManager.IMPORTANCE_LOW);
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        if (locationManager != null) locationManager.removeUpdates(this);
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(@NonNull String provider) {}
    @Override public void onProviderDisabled(@NonNull String provider) {}
}