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
import android.os.PowerManager;
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

    private static final String BASE_URL = "https://frotasapp.rondonopolis.mt.gov.br";
    private static final String CHANNEL_ID = "frota_pro_channel";
    private static final int NOTIFICATION_ID = 888;

    private LocationManager locationManager;
    private RequestQueue requestQueue;
    private String placa = "INICIANDO...";
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        requestQueue = Volley.newRequestQueue(this);

        // --- WAKELOCK (Correção V1 aplicada na V2) ---
        // Garante que o processador não durma mesmo com tela desligada
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Frota:GpsLock");
            wakeLock.acquire(12 * 60 * 60 * 1000L); // 12 horas de timeout seguro
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("placa")) {
            placa = intent.getStringExtra("placa");
        }

        // Recuperação de falha (Se o Android matar e reiniciar o serviço)
        if (placa == null || placa.equals("INICIANDO...") || placa.isEmpty()) {
            SharedPreferences prefs = getSharedPreferences("DadosViagem", Context.MODE_PRIVATE);
            placa = prefs.getString("placa_ativa", "DESCONHECIDO");
        }

        startForegroundServiceCompat();
        iniciarGpsAltaPrecisao();

        if (!placa.equals("INICIANDO...") && !placa.equals("DESCONHECIDO")) {
            enviarStatus("online");
        }

        return START_STICKY; // Garante que o serviço reinicie se for morto
    }

    private void startForegroundServiceCompat() {
        // Criar canal ANTES de criar a notificação (obrigatório Android O+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Rastreamento Frota", NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(channel);
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SGF Ativo")
                .setContentText("Monitorando: " + placa)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true) // Impede que o usuário limpe a notificação
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void iniciarGpsAltaPrecisao() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;

        try {
            // REMOVIDO: NetworkProvider (Causa saltos de localização)
            // APENAS GPS PROVIDER (Satélite puro)

            // minTime: 1000ms (1 segundo) para ter muitos pontos e filtrar no servidor
            // minDistance: 0 (queremos receber tudo para nós mesmos filtrarmos se está parado)
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);

        } catch (Exception e) {
            Log.e("GPS", "Erro ao iniciar: " + e.getMessage());
        }
    }

    @Override
    public void onLocationChanged(@NonNull Location loc) {
        if (placa.equals("INICIANDO...") || placa.equals("DESCONHECIDO")) return;

        // --- FILTRO DE PRECISÃO (CRÍTICO) ---
        // Descarta pontos com precisão ruim (> 25m)
        // Isso evita que o ponto pule prédios quando o sinal reflete
        if (loc.hasAccuracy() && loc.getAccuracy() > 25) {
            Log.d("GPS_FILTER", "Descartado: Precisão ruim (" + loc.getAccuracy() + "m)");
            return;
        }

        String url = BASE_URL + "/api/localizacao";
        JSONObject json = new JSONObject();
        try {
            json.put("placa", placa);
            json.put("latitude", loc.getLatitude());
            json.put("longitude", loc.getLongitude());

            // --- LÓGICA ANTI-DRIFT (Parado no Sinal) ---
            double velocidadeKmh = loc.getSpeed() * 3.6;

            // Se a velocidade for menor que 2km/h, consideramos 0 absoluto.
            // Isso impede o servidor de somar micro-distâncias no sinal vermelho.
            if (velocidadeKmh < 2.0) {
                velocidadeKmh = 0.0;
            }
            json.put("velocidade", velocidadeKmh);

        } catch (Exception e) {}

        JsonObjectRequest req = new JsonObjectRequest(Request.Method.POST, url, json, null,
                error -> Log.e("Volley", "Falha envio: " + error.toString()));

        // Timeout curto para não empilhar requisições em rede ruim
        req.setRetryPolicy(new DefaultRetryPolicy(4000, 1, 1f));
        requestQueue.add(req);
    }

    private void enviarStatus(String status) {
        String url = BASE_URL + "/api/status";
        JSONObject json = new JSONObject();
        try { json.put("placa", placa); json.put("status", status); } catch (Exception e) {}
        requestQueue.add(new JsonObjectRequest(Request.Method.POST, url, json, null, null));
    }

    @Override
    public void onDestroy() {
        if (locationManager != null) locationManager.removeUpdates(this);
        // Libera a CPU para dormir quando o app fechar de verdade
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(@NonNull String provider) {}
    @Override public void onProviderDisabled(@NonNull String provider) {}
}