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
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
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
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class LocationService extends Service {

    private static final String BASE_URL = "https://frotasapp.rondonopolis.mt.gov.br";
    private static final String CHANNEL_ID = "frota_nasa_channel";
    private static final int NOTIFICATION_ID = 333;

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private RequestQueue requestQueue;
    private String placa = "INICIANDO...";
    private PowerManager.WakeLock wakeLock;
    private KalmanLatLong kalmanFilter;

    // --- VARIÁVEIS DE INTELIGÊNCIA ARTIFICIAL (IA LÓGICA) ---
    private Location ancoraParado = null; // O ponto onde o carro "ancorou"
    private long ultimoTempoEnvio = 0;

    // O BUFFER MÁGICO (Guarda a arrancada)
    private List<Location> bufferArrancada = new ArrayList<>();

    // Configurações de Gatilho
    private static final float VELOCIDADE_MINIMA_MOVIMENTO = 4.0f; // km/h
    private static final float DISTANCIA_ROMPER_ANCORA = 15.0f; // metros

    // Intervalos de Envio
    private static final long INTERVALO_ENVIO_ANDANDO = 4000; // 4 segundos
    private static final long INTERVALO_ENVIO_PARADO = 120000; // 2 minutos (Heartbeat)

    @Override
    public void onCreate() {
        super.onCreate();

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        requestQueue = Volley.newRequestQueue(this);
        kalmanFilter = new KalmanLatLong(3);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SGF:NasaLock");
            wakeLock.acquire(12 * 60 * 60 * 1000L);
        }

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                if (locationResult == null) return;
                for (Location location : locationResult.getLocations()) {
                    analisarLocalizacao(location);
                }
            }
        };
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("placa")) {
            placa = intent.getStringExtra("placa");
        }
        if (placa == null || placa.isEmpty() || placa.equals("INICIANDO...")) {
            SharedPreferences prefs = getSharedPreferences("DadosViagem", Context.MODE_PRIVATE);
            placa = prefs.getString("placa_ativa", "DESCONHECIDO");
        }

        startForegroundServiceCompat();
        iniciarGPS();

        return START_STICKY;
    }

    private void iniciarGPS() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;

        // Pedimos atualização a cada 1 segundo (máximo do hardware)
        // Isso alimenta o Filtro de Kalman e o Buffer
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                .setMinUpdateIntervalMillis(500)
                .setWaitForAccurateLocation(false)
                .build();

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
    }

    private void analisarLocalizacao(Location rawLoc) {
        if (placa.equals("INICIANDO...") || placa.equals("DESCONHECIDO")) return;

        // 1. Descartar lixo de GPS (precisão ruim)
        if (rawLoc.getAccuracy() > 25) return;

        // 2. Aplicar Matemática de Kalman (Suavização)
        kalmanFilter.Process(rawLoc.getLatitude(), rawLoc.getLongitude(), rawLoc.getAccuracy(), rawLoc.getTime());

        // Objeto "Limpo"
        Location locAtual = new Location("Kalman");
        locAtual.setLatitude(kalmanFilter.get_lat());
        locAtual.setLongitude(kalmanFilter.get_lng());
        locAtual.setTime(System.currentTimeMillis());
        locAtual.setSpeed(rawLoc.getSpeed()); // Mantém velocidade original para cálculo

        double velKmh = rawLoc.getSpeed() * 3.6;
        long agora = System.currentTimeMillis();

        // --- LÓGICA CEREBRAL ---

        if (ancoraParado == null) {
            // Primeiro ponto do sistema: envia e define âncora
            enviarImediatamente(locAtual, velKmh);
            ancoraParado = locAtual;
            return;
        }

        float distanciaDaAncora = locAtual.distanceTo(ancoraParado);

        // ESTADO 1: CARRO PARECE PARADO (Velocidade baixa e perto da âncora)
        if (velKmh < VELOCIDADE_MINIMA_MOVIMENTO && distanciaDaAncora < DISTANCIA_ROMPER_ANCORA) {

            // Forçamos velocidade 0 visualmente
            locAtual.setSpeed(0);

            // Adiciona ao Buffer (Memória de curto prazo)
            // Se ele arrancar daqui a pouco, esses pontos serão úteis.
            bufferArrancada.add(locAtual);

            // Limita o buffer para não estourar memória (guarda últimos 30 segundos)
            if (bufferArrancada.size() > 30) bufferArrancada.remove(0);

            // Verifica Heartbeat (2 minutos)
            if (agora - ultimoTempoEnvio > INTERVALO_ENVIO_PARADO) {
                // Confirmado que está parado há muito tempo.
                // Limpa o buffer (era só ruído mesmo)
                bufferArrancada.clear();

                // Atualiza a âncora para a posição atual (recentraliza)
                ancoraParado = locAtual;
                enviarImediatamente(locAtual, 0.0);
            }
        }

        // ESTADO 2: CARRO ARRANCOU (Rompeu âncora OU velocidade alta)
        else {

            // AQUI ESTÁ A MÁGICA QUE VOCÊ PEDIU:
            // Se tivermos coisas no buffer, significa que ele estava acelerando mas a gente tava segurando.
            // Agora liberamos tudo de uma vez!
            if (!bufferArrancada.isEmpty()) {
                Log.d("GPS_SGF", "🚀 Arrancada detectada! Enviando " + bufferArrancada.size() + " pontos do buffer.");
                for (Location p : bufferArrancada) {
                    // Envia com velocidade real calculada
                    enviarImediatamente(p, p.getSpeed() * 3.6);
                }
                bufferArrancada.clear();
            }

            // Verifica o timer de 4 segundos para não flodar o banco
            if (agora - ultimoTempoEnvio > INTERVALO_ENVIO_ANDANDO) {
                enviarImediatamente(locAtual, velKmh);

                // Como ele está andando, a âncora "segue" ele para o próximo cálculo
                ancoraParado = locAtual;
            }
        }
    }

    private void enviarImediatamente(Location loc, double velKmh) {
        ultimoTempoEnvio = System.currentTimeMillis();

        String url = BASE_URL + "/api/localizacao";
        JSONObject json = new JSONObject();
        try {
            json.put("placa", placa);
            json.put("latitude", loc.getLatitude());
            json.put("longitude", loc.getLongitude());
            json.put("velocidade", velKmh);
        } catch (Exception e) {}

        JsonObjectRequest req = new JsonObjectRequest(Request.Method.POST, url, json, null,
                error -> Log.e("Volley", "Erro: " + error.toString()));

        // Timeout curto, sem retry (se perder 1 ponto a 80km/h não tem problema, vem outro em 4s)
        req.setRetryPolicy(new DefaultRetryPolicy(2000, 0, 1f));
        requestQueue.add(req);
    }

    private void startForegroundServiceCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "SGF Rastreamento Pro", NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(channel);
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SGF Ativo")
                .setContentText("Monitorando: " + placa)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public void onDestroy() {
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}