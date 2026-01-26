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

    // 🔴 CONFIRA SE O ENDEREÇO ESTÁ CORRETO
    private static final String BASE_URL = "https://frotasapp.rondonopolis.mt.gov.br";
    private static final String CHANNEL_ID = "frota_odometro_channel";
    private static final int NOTIFICATION_ID = 222;

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private RequestQueue requestQueue;
    private String placa = "INICIANDO...";
    private PowerManager.WakeLock wakeLock;
    private KalmanLatLong kalmanFilter;

    // --- VARIÁVEIS DE INTELIGÊNCIA ---
    private Location ancoraParado = null;
    private Location ultimaLocalizacaoCalculada = null; // Para somar KM metro a metro
    private double distanciaAcumuladaMetros = 0.0; // O HODÔMETRO DO APP

    private long ultimoTempoEnvio = 0;
    private List<Location> bufferArrancada = new ArrayList<>();

    // Configurações
    private static final float VELOCIDADE_MINIMA_MOVIMENTO = 4.0f; // km/h
    private static final float DISTANCIA_ROMPER_ANCORA = 15.0f; // metros
    private static final long INTERVALO_ENVIO_ANDANDO = 4000; // 4 seg
    private static final long INTERVALO_ENVIO_PARADO = 120000; // 2 min

    @Override
    public void onCreate() {
        super.onCreate();

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        requestQueue = Volley.newRequestQueue(this);
        kalmanFilter = new KalmanLatLong(3);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SGF:OdometerLock");
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

        // Alta frequência (1s) para calcular a distância com precisão nas curvas
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                .setMinUpdateIntervalMillis(500)
                .setWaitForAccurateLocation(false)
                .build();

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
    }

    private void analisarLocalizacao(Location rawLoc) {
        if (placa.equals("INICIANDO...") || placa.equals("DESCONHECIDO")) return;

        if (rawLoc.getAccuracy() > 25) return;

        // Filtro Kalman
        kalmanFilter.Process(rawLoc.getLatitude(), rawLoc.getLongitude(), rawLoc.getAccuracy(), rawLoc.getTime());

        Location locAtual = new Location("Kalman");
        locAtual.setLatitude(kalmanFilter.get_lat());
        locAtual.setLongitude(kalmanFilter.get_lng());
        locAtual.setTime(System.currentTimeMillis());
        locAtual.setSpeed(rawLoc.getSpeed());

        double velKmh = rawLoc.getSpeed() * 3.6;
        long agora = System.currentTimeMillis();

        // --- 1. CÁLCULO DO HODÔMETRO (IMPORTANTE) ---
        // Calculamos a distância a cada 1 segundo, independente se vamos enviar ou não.
        // Isso garante que curvas sejam somadas corretamente.
        if (ultimaLocalizacaoCalculada != null) {
            // Só soma se velocidade > 3km/h (Evita somar ruído parado)
            if (velKmh > 3.0) {
                float d = locAtual.distanceTo(ultimaLocalizacaoCalculada);
                distanciaAcumuladaMetros += d;
            }
        }
        ultimaLocalizacaoCalculada = locAtual;
        // ---------------------------------------------

        // --- 2. LÓGICA DE ENVIO (SMART THROTTLING) ---

        if (ancoraParado == null) {
            enviarImediatamente(locAtual, velKmh);
            ancoraParado = locAtual;
            return;
        }

        float distanciaDaAncora = locAtual.distanceTo(ancoraParado);

        // ESTADO: PARADO
        if (velKmh < VELOCIDADE_MINIMA_MOVIMENTO && distanciaDaAncora < DISTANCIA_ROMPER_ANCORA) {
            locAtual.setSpeed(0);
            bufferArrancada.add(locAtual);
            if (bufferArrancada.size() > 30) bufferArrancada.remove(0);

            if (agora - ultimoTempoEnvio > INTERVALO_ENVIO_PARADO) {
                bufferArrancada.clear();
                ancoraParado = locAtual;
                enviarImediatamente(locAtual, 0.0);
            }
        }
        // ESTADO: ANDANDO
        else {
            if (!bufferArrancada.isEmpty()) {
                // Se tinha buffer, soma a distância deles também para não perder nada
                // (Opcional, mas ajuda na precisão fina)
                for (Location p : bufferArrancada) {
                    enviarImediatamente(p, p.getSpeed() * 3.6);
                }
                bufferArrancada.clear();
            }

            if (agora - ultimoTempoEnvio > INTERVALO_ENVIO_ANDANDO) {
                enviarImediatamente(locAtual, velKmh);
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

            // --- AQUI ESTÁ A CORREÇÃO: ENVIAMOS O TOTAL ACUMULADO ---
            json.put("distancia_percorrida", distanciaAcumuladaMetros);
            // --------------------------------------------------------

        } catch (Exception e) {}

        JsonObjectRequest req = new JsonObjectRequest(Request.Method.POST, url, json, null,
                error -> Log.e("Volley", "Erro: " + error.toString()));

        req.setRetryPolicy(new DefaultRetryPolicy(2000, 0, 1f));
        requestQueue.add(req);
    }

    private void startForegroundServiceCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "SGF Rastreamento", NotificationManager.IMPORTANCE_LOW);
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