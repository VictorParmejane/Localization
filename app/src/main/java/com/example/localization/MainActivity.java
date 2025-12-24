package com.example.localization;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.FirebaseApp;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.GeoPoint;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.WriteBatch;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    // Componentes da Interface
    private EditText edtNome, edtCpf, edtCelular, edtPlaca, edtHodometro, edtDestino, edtTipoServico;
    private Button btnToggle;
    private TextView txtHora, txtStatus;
    private ImageView imgStatus;

    // Variáveis de Controle
    private boolean rastreando = false;
    private Handler handler = new Handler();
    private Runnable horaRunnable;

    // Firebase
    private FirebaseFirestore db;
    private FirebaseAuth mAuth;

    // Constantes
    private static final int REQ_LOC_PERMISSIONS = 101;
    private static final String PREFS = "localization_prefs";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Inicializa Firebase
        FirebaseApp.initializeApp(this);
        db = FirebaseFirestore.getInstance();
        mAuth = FirebaseAuth.getInstance();
        if (mAuth.getCurrentUser() == null) mAuth.signInAnonymously();

        // Inicializa a Tela
        initViews();
        atualizarHora();
        configurarAutoPreenchimento();

        // Restaura se o app fechou e abriu de novo
        restaurarEstado();

        // Ação do Botão Principal
        btnToggle.setOnClickListener(v -> {
            if (rastreando) {
                dialogoFinalizarViagem();
            } else {
                if (validarCampos()) {
                    verificarPlacaEUsuario();
                }
            }
        });
    }

    private void initViews() {
        edtNome = findViewById(R.id.edtNome);
        edtCpf = findViewById(R.id.edtCpf);
        edtCelular = findViewById(R.id.edtCelular);
        edtPlaca = findViewById(R.id.edtPlaca);
        edtHodometro = findViewById(R.id.edtHodometro);
        edtDestino = findViewById(R.id.edtDestino);
        edtTipoServico = findViewById(R.id.edtTipoServico);
        btnToggle = findViewById(R.id.btnToggle);
        txtHora = findViewById(R.id.txtHora);
        txtStatus = findViewById(R.id.txtStatus);
        imgStatus = findViewById(R.id.imgStatus);

        edtCpf.addTextChangedListener(insertMask("###.###.###-##", edtCpf));
        edtCelular.addTextChangedListener(insertMask("(##) #####-####", edtCelular));
    }

    // --- LÓGICA DE INICIAR ---

    private void verificarPlacaEUsuario() {
        String cpfLimpo = unmask(edtCpf.getText().toString());
        String placaLimpa = edtPlaca.getText().toString().trim().toUpperCase().replace("-", "");

        if (cpfLimpo.length() < 11) {
            Toast.makeText(this, "CPF incompleto", Toast.LENGTH_SHORT).show();
            return;
        }

        // 1. Salva o USUÁRIO (Usando CPF como ID para evitar duplicatas)
        // Se mudar o nome, ele atualiza o documento existente.
        Map<String, Object> u = new HashMap<>();
        u.put("name", edtNome.getText().toString());
        u.put("cpf", edtCpf.getText().toString());
        u.put("celular", edtCelular.getText().toString());
        db.collection("users").document(cpfLimpo).set(u, SetOptions.merge());

        // 2. Verifica permissão de GPS
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOC_PERMISSIONS);
        } else {
            salvarAmbulanciaEIniciar(placaLimpa);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOC_PERMISSIONS && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            String placaLimpa = edtPlaca.getText().toString().trim().toUpperCase().replace("-", "");
            salvarAmbulanciaEIniciar(placaLimpa);
        }
    }

    private void salvarAmbulanciaEIniciar(String placaLimpaID) {
        Date now = new Date();
        String dataAtual = new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(now);
        String horaAtual = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(now);

        Map<String, Object> dados = new HashMap<>();
        dados.put("placa", edtPlaca.getText().toString());
        dados.put("cpf", edtCpf.getText().toString());
        dados.put("name", edtNome.getText().toString());
        dados.put("celular", edtCelular.getText().toString());
        dados.put("hodometro_entrada", edtHodometro.getText().toString());
        dados.put("destino", edtDestino.getText().toString());
        dados.put("tipo_servico", edtTipoServico.getText().toString());
        dados.put("data_entrada", dataAtual);
        dados.put("horario_entrada", horaAtual);
        dados.put("lastupdate", Timestamp.now());
        dados.put("status", "online");

        // Inicializa campos vazios de saída
        dados.put("hodometro_saida", "");
        dados.put("data_saida", "");
        dados.put("horario_saida", "");
        dados.put("location", new GeoPoint(0, 0));

        // Salva na coleção 'ambulances' (Carros Ativos)
        db.collection("ambulances").document(placaLimpaID)
                .set(dados, SetOptions.merge())
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(MainActivity.this, "Iniciando Viagem...", Toast.LENGTH_SHORT).show();
                    iniciarServicoBackground(dataAtual, horaAtual);
                    rastreando = true;
                    setOnlineUI();
                    salvarEstado(true, dataAtual, horaAtual);
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(MainActivity.this, "ERRO AO SALVAR: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
    }

    private void iniciarServicoBackground(String data, String hora) {
        Intent intent = new Intent(this, LocationService.class);
        intent.putExtra("placa", edtPlaca.getText().toString());
        intent.putExtra("cpf", edtCpf.getText().toString());
        intent.putExtra("name", edtNome.getText().toString());
        intent.putExtra("celular", edtCelular.getText().toString());
        intent.putExtra("hodometro_entrada", edtHodometro.getText().toString());
        intent.putExtra("destino", edtDestino.getText().toString());
        intent.putExtra("tipo_servico", edtTipoServico.getText().toString());
        intent.putExtra("data_entrada", data);
        intent.putExtra("horario_entrada", hora);
        ContextCompat.startForegroundService(this, intent);
    }

    // --- LÓGICA DE FINALIZAR ---

    private void dialogoFinalizarViagem() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Finalizar Viagem");
        builder.setMessage("Digite o Hodômetro de Saída:");
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        builder.setView(input);

        builder.setPositiveButton("Finalizar", (dialog, which) -> {
            String val = input.getText().toString();
            if (!val.isEmpty()) processarFimViagem(val);
        });
        builder.setNegativeButton("Cancelar", null);
        builder.show();
    }

    private void processarFimViagem(String hodometroSaida) {
        String placaLimpa = edtPlaca.getText().toString().trim().toUpperCase().replace("-", "");
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Date now = new Date();

        // 1. Prepara dados do Histórico
        Map<String, Object> hist = new HashMap<>();
        hist.put("placa", edtPlaca.getText().toString());
        hist.put("name", edtNome.getText().toString());
        hist.put("cpf", edtCpf.getText().toString());
        hist.put("celular", edtCelular.getText().toString());
        hist.put("hodometro_entrada", edtHodometro.getText().toString());
        hist.put("hodometro_saida", hodometroSaida);
        hist.put("destino", edtDestino.getText().toString());
        hist.put("tipo_servico", edtTipoServico.getText().toString());
        hist.put("data_entrada", prefs.getString("saved_data_entrada", ""));
        hist.put("horario_entrada", prefs.getString("saved_horario_entrada", ""));
        hist.put("data_saida", new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(now));
        hist.put("horario_saida", new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(now));
        hist.put("lastupdate", Timestamp.now());

        // 2. Prepara dados de Atualização do VEÍCULO (Novo Recurso)
        Map<String, Object> dadosVeiculo = new HashMap<>();
        dadosVeiculo.put("placa", edtPlaca.getText().toString());
        dadosVeiculo.put("last_odometer", hodometroSaida); // Salva o KM atual para a próxima vez
        dadosVeiculo.put("last_driver", edtNome.getText().toString());
        dadosVeiculo.put("updated_at", Timestamp.now());

        // 3. Executa em Lote (Batch) - Mais seguro
        WriteBatch batch = db.batch();

        // A. Cria registro no Histórico (ID Automático)
        batch.set(db.collection("historico_viagem").document(), hist);

        // B. Atualiza cadastro do Veículo (ID = Placa)
        batch.set(db.collection("vehicles").document(placaLimpa), dadosVeiculo, SetOptions.merge());

        // C. Deleta da lista de ativos (Ambulances)
        batch.delete(db.collection("ambulances").document(placaLimpa));

        batch.commit().addOnSuccessListener(aVoid -> {
            pararServico();
            Toast.makeText(MainActivity.this, "Viagem Finalizada e KM Atualizado!", Toast.LENGTH_SHORT).show();
            limparCampos();
        }).addOnFailureListener(e -> {
            Toast.makeText(MainActivity.this, "Erro ao finalizar: " + e.getMessage(), Toast.LENGTH_LONG).show();
        });
    }

    private void pararServico() {
        stopService(new Intent(this, LocationService.class));
        rastreando = false;
        setOfflineUI();
        salvarEstado(false, "", "");
    }

    private void limparCampos() {
        edtPlaca.setText("");
        edtHodometro.setText("");
        edtDestino.setText("");
        edtTipoServico.setText("");
        // Nome, CPF e Celular mantemos preenchidos para facilitar
    }

    // --- UTILITÁRIOS E UI ---

    private void configurarAutoPreenchimento() {
        // Busca Usuário pelo CPF
        edtCpf.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                String cpfLimpo = unmask(edtCpf.getText().toString());
                if (cpfLimpo.length() >= 11) {
                    db.collection("users").document(cpfLimpo).get().addOnSuccessListener(doc -> {
                        if (doc.exists()) {
                            if (doc.contains("name")) edtNome.setText(doc.getString("name"));
                            if (doc.contains("celular")) edtCelular.setText(doc.getString("celular"));
                        }
                    });
                }
            }
        });

        // Busca Hodômetro na coleção 'vehicles' pela Placa
        edtPlaca.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                String p = edtPlaca.getText().toString().trim().toUpperCase().replace("-", "");
                if (!p.isEmpty()) {
                    // MUDANÇA: Agora busca na coleção de veículos, que é mais leve e precisa
                    db.collection("vehicles").document(p).get()
                            .addOnSuccessListener(doc -> {
                                if (doc.exists()) {
                                    String km = doc.getString("last_odometer");
                                    if (km != null && !km.isEmpty()) {
                                        edtHodometro.setText(km); // Preenche automaticamente
                                        Toast.makeText(MainActivity.this, "Hodômetro recuperado: " + km + " Km", Toast.LENGTH_SHORT).show();
                                    }
                                } else {
                                    Toast.makeText(MainActivity.this, "Veículo novo. Digite o KM inicial.", Toast.LENGTH_SHORT).show();
                                }
                            });
                }
            }
        });
    }

    private void salvarEstado(boolean ativo, String d, String h) {
        SharedPreferences.Editor ed = getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        ed.putBoolean("tracking_active", ativo);
        ed.putString("name", edtNome.getText().toString());
        ed.putString("cpf", edtCpf.getText().toString());
        ed.putString("celular", edtCelular.getText().toString());
        ed.putString("placa", edtPlaca.getText().toString());
        ed.putString("hodometro_entrada", edtHodometro.getText().toString());
        ed.putString("destino", edtDestino.getText().toString());
        ed.putString("tipo_servico", edtTipoServico.getText().toString());
        if (ativo) {
            ed.putString("saved_data_entrada", d);
            ed.putString("saved_horario_entrada", h);
        }
        ed.apply();
    }

    private void restaurarEstado() {
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        rastreando = prefs.getBoolean("tracking_active", false);

        edtNome.setText(prefs.getString("name", ""));
        edtCpf.setText(prefs.getString("cpf", ""));
        edtCelular.setText(prefs.getString("celular", ""));
        edtPlaca.setText(prefs.getString("placa", ""));
        edtHodometro.setText(prefs.getString("hodometro_entrada", ""));
        edtDestino.setText(prefs.getString("destino", ""));
        edtTipoServico.setText(prefs.getString("tipo_servico", ""));

        if (rastreando) {
            setOnlineUI();
            String d = prefs.getString("saved_data_entrada", "");
            String h = prefs.getString("saved_horario_entrada", "");
            // Garante que o serviço esteja rodando caso o app tenha sido morto
            iniciarServicoBackground(d, h);
        } else {
            setOfflineUI();
        }
    }

    private void atualizarHora() {
        horaRunnable = new Runnable() {
            @Override
            public void run() {
                String horaAtual = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                txtHora.setText(horaAtual);
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(horaRunnable);
    }

    private boolean validarCampos() {
        if (edtNome.getText().toString().isEmpty() || edtCpf.getText().toString().isEmpty() ||
                edtPlaca.getText().toString().isEmpty() || edtHodometro.getText().toString().isEmpty()) {
            Toast.makeText(this, "Preencha os campos obrigatórios.", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private void setOnlineUI() {
        btnToggle.setText("FINALIZAR VIAGEM");
        btnToggle.setBackgroundColor(getColor(android.R.color.holo_red_dark));
        txtStatus.setText("Em Rota: " + edtPlaca.getText().toString());
        txtStatus.setTextColor(getColor(android.R.color.holo_green_dark));
        imgStatus.setImageResource(android.R.drawable.presence_online);
        imgStatus.setColorFilter(getColor(android.R.color.holo_green_dark));
        bloquearCampos(true);
    }

    private void setOfflineUI() {
        btnToggle.setText("INICIAR VIAGEM");
        btnToggle.setBackgroundColor(getColor(android.R.color.holo_green_dark));
        txtStatus.setText("Veículo Parado");
        txtStatus.setTextColor(getColor(android.R.color.holo_red_dark));
        imgStatus.setImageResource(android.R.drawable.presence_busy);
        imgStatus.setColorFilter(getColor(android.R.color.holo_red_dark));
        bloquearCampos(false);
    }

    private void bloquearCampos(boolean b) {
        edtNome.setEnabled(!b); edtCpf.setEnabled(!b); edtCelular.setEnabled(!b);
        edtPlaca.setEnabled(!b); edtHodometro.setEnabled(!b); edtDestino.setEnabled(!b);
        edtTipoServico.setEnabled(!b);
    }

    private String unmask(String s) {
        return s.replaceAll("[.]", "").replaceAll("[-]", "").replaceAll("[/]", "")
                .replaceAll("[(]", "").replaceAll("[)]", "").replaceAll(" ", "");
    }

    private TextWatcher insertMask(final String mask, final EditText ediTxt) {
        return new TextWatcher() {
            boolean isUpdating; String old = "";
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String str = unmask(s.toString()); String mascara = "";
                if (isUpdating) { old = str; isUpdating = false; return; }
                int i = 0;
                for (char m : mask.toCharArray()) {
                    if (m != '#' && str.length() > old.length()) { mascara += m; continue; }
                    try { mascara += str.charAt(i); } catch (Exception e) { break; } i++;
                }
                isUpdating = true; ediTxt.setText(mascara);
                try { ediTxt.setSelection(mascara.length()); } catch (Exception e) {}
            }
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void afterTextChanged(Editable s) {}
        };
    }
}