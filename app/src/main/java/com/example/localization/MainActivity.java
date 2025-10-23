package com.example.localization;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private EditText edtNome;
    private Button btnToggle;
    private TextView txtHora, txtStatus;
    private ImageView imgStatus;

    private boolean rastreando = false;
    private Handler handler = new Handler();
    private Runnable horaRunnable;

    private static final int REQ_LOC_PERMISSIONS = 101;
    private static final String PREFS = "localization_prefs";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        edtNome = findViewById(R.id.edtNome);
        btnToggle = findViewById(R.id.btnToggle);
        txtHora = findViewById(R.id.txtHora);
        txtStatus = findViewById(R.id.txtStatus);
        imgStatus = findViewById(R.id.imgStatus);

        atualizarHora();

        // 🔹 Restaura estado salvo
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        rastreando = prefs.getBoolean("tracking_active", false);
        if (rastreando) {
            String nomeSalvo = prefs.getString("user_name", "");
            edtNome.setText(nomeSalvo);
            setOnlineUI(nomeSalvo);
        } else {
            setOfflineUI();
        }

        btnToggle.setOnClickListener(v -> {
            if (rastreando) {
                pararServico();
            } else {
                verificarPermissoesEIniciar();
            }
        });
    }

    /** 🔹 Atualiza a hora local na tela a cada segundo */
    private void atualizarHora() {
        horaRunnable = new Runnable() {
            @Override
            public void run() {
                String horaAtual = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                        .format(new Date());
                txtHora.setText(horaAtual);
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(horaRunnable);
    }

    private void verificarPermissoesEIniciar() {
        String[] permissions = {
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.FOREGROUND_SERVICE_LOCATION
        };

        boolean fine = ContextCompat.checkSelfPermission(this, permissions[0]) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(this, permissions[1]) == PackageManager.PERMISSION_GRANTED;
        boolean fgService = ContextCompat.checkSelfPermission(this, permissions[2]) == PackageManager.PERMISSION_GRANTED;

        if (!fine || !coarse || !fgService) {
            ActivityCompat.requestPermissions(this, permissions, REQ_LOC_PERMISSIONS);
        } else {
            iniciarServico();
        }
    }

    private void iniciarServico() {
        String nome = edtNome.getText().toString().trim();
        if (nome.isEmpty()) {
            Toast.makeText(this, "Digite seu nome antes de iniciar.", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(this, LocationService.class);
        intent.putExtra("user_name", nome);
        ContextCompat.startForegroundService(this, intent);

        rastreando = true;
        salvarEstado(true, nome);
        setOnlineUI(nome);
    }

    private void pararServico() {
        Intent i = new Intent(this, LocationService.class);
        stopService(i);

        rastreando = false;
        salvarEstado(false, "");
        setOfflineUI();
    }

    /** 🔹 Salva ou limpa estado do serviço para persistir entre aberturas */
    private void salvarEstado(boolean ativo, String nome) {
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = prefs.edit();
        ed.putBoolean("tracking_active", ativo);
        ed.putString("user_name", nome);
        ed.apply();
    }

    /** ---------------------- UI Helpers ---------------------- **/
    private void setOnlineUI(String nome) {
        btnToggle.setText("Parar Rastreamento");
        txtStatus.setText("Online (" + nome + ")");
        txtStatus.setTextColor(getColor(android.R.color.holo_green_dark));
        imgStatus.setImageResource(android.R.drawable.presence_online);
        imgStatus.setColorFilter(getColor(android.R.color.holo_green_dark));
    }

    private void setOfflineUI() {
        btnToggle.setText("Iniciar Rastreamento");
        txtStatus.setText("Offline");
        txtStatus.setTextColor(getColor(android.R.color.holo_red_dark));
        imgStatus.setImageResource(android.R.drawable.presence_busy);
        imgStatus.setColorFilter(getColor(android.R.color.holo_red_dark));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOC_PERMISSIONS) {
            boolean granted = true;
            for (int res : grantResults) {
                if (res != PackageManager.PERMISSION_GRANTED) granted = false;
            }
            if (granted) iniciarServico();
            else Toast.makeText(this, "Permissões necessárias.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(horaRunnable);
    }
}