package com.example.localization;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.FirebaseApp;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private EditText edtPlaca, edtNome;
    private Button btnToggle;
    private TextView txtStatus;
    private ImageView imgStatus;

    private boolean rastreando = false;
    private FirebaseFirestore db;
    private FirebaseAuth mAuth;

    private static final int REQ_LOC_PERMISSIONS = 101;
    private static final String PREFS = "localization_prefs";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        FirebaseApp.initializeApp(this);
        db = FirebaseFirestore.getInstance();
        mAuth = FirebaseAuth.getInstance();
        if (mAuth.getCurrentUser() == null) mAuth.signInAnonymously();

        initViews();
        restaurarEstado();

        btnToggle.setOnClickListener(v -> {
            if (rastreando) {
                pararRastreamento();
            } else {
                iniciarRastreamento();
            }
        });
    }

    private void initViews() {
        edtPlaca = findViewById(R.id.edtPlaca);
        edtNome = findViewById(R.id.edtNome);
        btnToggle = findViewById(R.id.btnToggle);
        txtStatus = findViewById(R.id.txtStatus);
        imgStatus = findViewById(R.id.imgStatus);
    }

    private void iniciarRastreamento() {
        String placa = edtPlaca.getText().toString().trim().toUpperCase();
        String nome = edtNome.getText().toString().trim();

        if (placa.isEmpty() || nome.isEmpty()) {
            Toast.makeText(this, "Informe Placa e Motorista", Toast.LENGTH_SHORT).show();
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS}, REQ_LOC_PERMISSIONS);
            return;
        }

        // 1. Atualiza no Firebase que este carro está ONLINE
        String docId = placa.replace("-", "");
        Map<String, Object> dados = new HashMap<>();
        dados.put("placa", placa);
        dados.put("motorista_atual", nome);
        dados.put("status", "online");
        dados.put("last_connection", Timestamp.now());

        db.collection("ambulances").document(docId)
                .set(dados, SetOptions.merge())
                .addOnSuccessListener(aVoid -> {
                    // 2. Inicia o Serviço
                    Intent intent = new Intent(this, LocationService.class);
                    intent.putExtra("placa", placa);
                    intent.putExtra("motorista", nome);
                    ContextCompat.startForegroundService(this, intent);

                    rastreando = true;
                    setOnlineUI();
                    salvarEstado(true);
                    Toast.makeText(this, "Rastreamento Iniciado", Toast.LENGTH_SHORT).show();
                })
                .addOnFailureListener(e -> Toast.makeText(this, "Erro de conexão", Toast.LENGTH_SHORT).show());
    }

    private void pararRastreamento() {
        String placa = edtPlaca.getText().toString().trim().replace("-", "");

        // Atualiza status para offline no banco
        if(!placa.isEmpty()){
            db.collection("ambulances").document(placa).update("status", "offline");
        }

        stopService(new Intent(this, LocationService.class));
        rastreando = false;
        setOfflineUI();
        salvarEstado(false);
        Toast.makeText(this, "Rastreamento Parado", Toast.LENGTH_SHORT).show();
    }

    // --- UI e Estado ---

    private void setOnlineUI() {
        btnToggle.setText("PARAR RASTREAMENTO");
        btnToggle.setBackgroundColor(getColor(android.R.color.holo_red_dark));
        txtStatus.setText("Enviando Localização...");
        txtStatus.setTextColor(getColor(android.R.color.holo_green_dark));
        imgStatus.setImageResource(android.R.drawable.presence_online);
        imgStatus.setColorFilter(getColor(android.R.color.holo_green_dark));
        edtPlaca.setEnabled(false);
        edtNome.setEnabled(false);
    }

    private void setOfflineUI() {
        btnToggle.setText("ATIVAR RASTREAMENTO");
        btnToggle.setBackgroundColor(getColor(android.R.color.holo_green_dark));
        txtStatus.setText("Rastreamento Inativo");
        txtStatus.setTextColor(getColor(android.R.color.holo_red_dark));
        imgStatus.setImageResource(android.R.drawable.presence_offline);
        imgStatus.setColorFilter(getColor(android.R.color.holo_red_dark));
        edtPlaca.setEnabled(true);
        edtNome.setEnabled(true);
    }

    private void salvarEstado(boolean ativo) {
        SharedPreferences.Editor ed = getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        ed.putBoolean("tracking_active", ativo);
        ed.putString("placa", edtPlaca.getText().toString());
        ed.putString("nome", edtNome.getText().toString());
        ed.apply();
    }

    private void restaurarEstado() {
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        rastreando = prefs.getBoolean("tracking_active", false);
        edtPlaca.setText(prefs.getString("placa", ""));
        edtNome.setText(prefs.getString("nome", ""));

        if (rastreando) {
            setOnlineUI();
            // Garante que o serviço está rodando se o app foi reiniciado
            Intent intent = new Intent(this, LocationService.class);
            intent.putExtra("placa", prefs.getString("placa", ""));
            intent.putExtra("motorista", prefs.getString("nome", ""));
            ContextCompat.startForegroundService(this, intent);
        } else {
            setOfflineUI();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOC_PERMISSIONS && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            iniciarRastreamento();
        }
    }
}