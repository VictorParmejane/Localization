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
import android.os.Bundle; // Mantido para compatibilidade
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

// Importações do Volley (Requisição HTTP)
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONObject;

public class LocationService extends Service implements LocationListener {

    // --- CONFIGURAÇÃO DO SERVIDOR ---
    // SUBSTITUA O "192.168.X.X" PELO SEU IPV4 DO 'ipconfig'
    // Mantenha o :3000 e o /api/localizacao
    private static final String SERVER_URL = "http://192.168.18.25:3000/api/localizacao";

    private static final String CHANNEL_ID = "FROTA_TRACKER_CHANNEL";
    private static final int NOTIFICATION_ID = 300;

    private LocationManager locationManager;
    private RequestQueue requestQueue; // Fila de envios do Volley

    private boolean isRodando = false;
    private String placa = "DESCONHECIDO";
    private String motorista = "DESCONHECIDO";

    @Override
    public void onCreate() {
        super.onCreate();
        // Inicializa o gerenciador de localização e a fila de internet
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        requestQueue = Volley.newRequestQueue(this);

        criarNotificacao();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String p = intent.getStringExtra("placa");
            String m = intent.getStringExtra("motorista");
            if (p != null) placa = p;
            if (m != null) motorista = m;
        }

        if (!isRodando) {
            isRodando = true;
            iniciarListenerGps();
        }

        atualizarNotificacao("Monitorando: " + placa);
        return START_STICKY;
    }

    // --- LÓGICA DE GPS ---

    private void iniciarListenerGps() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        // Tenta pegar localização via GPS (Satélite)
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 5f, this, Looper.getMainLooper());

        // Tenta pegar via Rede (Wi-Fi/4G) como backup
        locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000, 10f, this, Looper.getMainLooper());
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        if (isRodando) {
            // AQUI É O PULO DO GATO: GPS Mudou -> Envia pro PC
            enviarParaServidor(location);
        }
    }

    // --- LÓGICA DE ENVIO (HTTP POST) ---

    private void enviarParaServidor(Location loc) {
        JSONObject dados = new JSONObject();
        try {
            dados.put("placa", placa);
            dados.put("motorista", motorista);
            dados.put("latitude", loc.getLatitude());
            dados.put("longitude", loc.getLongitude());
            dados.put("speed", loc.getSpeed() * 3.6); // Converte m/s para km/h
        } catch (Exception e) {
            Log.e("JSON", "Erro ao criar JSON: " + e.getMessage());
            return;
        }

        // Cria a requisição
        JsonObjectRequest request = new JsonObjectRequest(Request.Method.POST, SERVER_URL, dados,
                response -> {
                    // Sucesso (Servidor respondeu 200 OK)
                    Log.d("API_FROTA", "Enviado com sucesso: " + loc.getLatitude());
                },
                error -> {
                    // Erro (Servidor desligado, IP errado ou Firewall bloqueando)
                    Log.e("API_FROTA", "ERRO AO ENVIAR: " + error.toString());
                    if(error.networkResponse != null) {
                        Log.e("API_FROTA", "Status Code: " + error.networkResponse.statusCode);
                    }
                }
        );

        // Adiciona na fila para ser enviado
        requestQueue.add(request);
    }

    // --- CÓDIGO PADRÃO ANDROID (NOTIFICAÇÃO E LIMPEZA) ---

    private void criarNotificacao() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel chan = new NotificationChannel(CHANNEL_ID, "Rastreamento Frota", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(chan);
        }
        startForeground(NOTIFICATION_ID, buildNotification("Aguardando sinal GPS..."));
    }

    private void atualizarNotificacao(String texto) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, buildNotification(texto));
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("App Frota Ativo")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        isRodando = false;
        if (locationManager != null) locationManager.removeUpdates(this);
        stopForeground(true);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    // Métodos vazios necessários para versões antigas do Android, pode ignorar
    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(@NonNull String provider) {}
    @Override public void onProviderDisabled(@NonNull String provider) {}
}