package com.example.localization;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context; // Importante
import android.content.Intent;
import android.content.SharedPreferences; // Importante
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

    // 🔴 CONFIRA SEU IP
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
        // 1. Tenta pegar do Intent (Quando vem da Activity)
        if (intent != null && intent.hasExtra("placa")) {
            String p = intent.getStringExtra("placa");
            if (p != null && !p.isEmpty()) {
                placa = p;
            }
        }

        // 2. RECUPERAÇÃO DE MEMÓRIA (Salva-vidas para o "Run")
        // Se a placa estiver inválida, tenta ler do disco
        if (placa == null || placa.equals("INICIANDO...") || placa.isEmpty()) {
            SharedPreferences prefs = getSharedPreferences("DadosViagem", Context.MODE_PRIVATE);
            String placaSalva = prefs.getString("placa_ativa", "");

            if (!placaSalva.isEmpty()) {
                placa = placaSalva;
                Log.d("LocationService", "Placa recuperada da memória: " + placa);
            }
        }

        startForegroundServiceCompat();
        iniciarListenerGps();

        // Só envia status se tiver placa válida
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
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 2f, this);
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000, 5f, this);
        } catch (Exception e) {
            Log.e("GPS", "Erro: " + e.getMessage());
        }
    }

    @Override
    public void onLocationChanged(@NonNull Location loc) {
        // Se a placa ainda não foi carregada, não envia nada para evitar Erro 500
        if (placa.equals("INICIANDO...")) return;

        String url = BASE_URL + "/api/localizacao";
        JSONObject json = new JSONObject();
        try {
            json.put("placa", placa);
            json.put("latitude", loc.getLatitude());
            json.put("longitude", loc.getLongitude());
            json.put("velocidade", loc.getSpeed() * 3.6);
        } catch (Exception e) {}

        JsonObjectRequest req = new JsonObjectRequest(Request.Method.POST, url, json, null,
                error -> {
                    if (error.networkResponse != null) {
                        Log.e("Volley", "Erro Servidor: " + error.networkResponse.statusCode);
                    } else {
                        Log.e("Volley", "Erro Rede: " + error.getMessage());
                    }
                });

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