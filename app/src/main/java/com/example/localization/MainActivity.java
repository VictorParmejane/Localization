package com.example.localization;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    // 🔴 ATENÇÃO: VERIFIQUE SEU LINK DO NGROK
    private static final String SERVER_BASE = "https://frotasapp.rondonopolis.mt.gov.br";
    private static final String FORM_URL = SERVER_BASE + "/mobile?modo=app";

    private LinearLayout layoutFormulario, layoutRastreamento;
    private WebView webView;
    private Button btnToggle;
    private TextView txtHora, txtStatus;

    private boolean operacaoAtiva = false;
    private String placaAtual = "";
    private Handler handler = new Handler();
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Intent intent = getIntent();
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            Uri data = intent.getData();
            if (data != null) {
                String placaLink = data.getQueryParameter("placa");
                if (placaLink != null && !placaLink.isEmpty()) {
                    this.placaAtual = placaLink;
                    // Se já tiver permissão, inicia direto. Se não, o fluxo normal pede.
                    // Aqui você pode salvar no SharedPreferences para garantir
                    getSharedPreferences("DadosViagem", Context.MODE_PRIVATE)
                            .edit().putString("placa_ativa", placaLink).apply();

                    // Força o WebView a carregar já com a placa se não estiver em viagem
                    if (!operacaoAtiva) {
                        // Pequeno delay para garantir que a WebView carregou
                        new Handler().postDelayed(() -> {
                            webView.loadUrl(FORM_URL + "&placa=" + placaLink);
                        }, 1000);
                    }
                }
            }
        }

        prefs = getSharedPreferences("DadosViagem", Context.MODE_PRIVATE);

        layoutFormulario = findViewById(R.id.layoutFormulario);
        layoutRastreamento = findViewById(R.id.layoutRastreamento);
        webView = findViewById(R.id.webview);
        btnToggle = findViewById(R.id.btnToggle);
        txtHora = findViewById(R.id.txtHora);
        txtStatus = findViewById(R.id.txtStatus);

        configurarWebView();
        iniciarRelogio();
        recuperarEstadoViagem();

        btnToggle.setOnClickListener(v -> abrirDialogoEncerramento());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (operacaoAtiva) {
                    Toast.makeText(MainActivity.this, "Finalize a viagem primeiro.", Toast.LENGTH_SHORT).show();
                } else if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    moveTaskToBack(true);
                }
            }
        });
    }

    private void salvarEstadoViagem(String placa) {
        prefs.edit().putString("placa_ativa", placa).putBoolean("em_viagem", true).apply();
        this.placaAtual = placa;
        this.operacaoAtiva = true;
    }

    private void limparEstadoViagem() {
        prefs.edit().clear().apply();
        this.placaAtual = "";
        this.operacaoAtiva = false;
    }

    private void recuperarEstadoViagem() {
        boolean emViagem = prefs.getBoolean("em_viagem", false);
        String placaSalva = prefs.getString("placa_ativa", "");

        if (emViagem && !placaSalva.isEmpty()) {
            this.placaAtual = placaSalva;
            this.operacaoAtiva = true;
            alternarTelaParaRastreamento();
            Intent intent = new Intent(this, LocationService.class);
            intent.putExtra("placa", placaAtual);
            ContextCompat.startForegroundService(this, intent);
        }
    }

    private void configurarWebView() {
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        webView.addJavascriptInterface(new WebAppInterface(this), "Android");

        // --- LIMPEZA DE CACHE PARA GARANTIR REGRAS NOVAS ---
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
        webView.clearCache(true);
        // ---------------------------------------------------

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Assim que o site carregar, o Android preenche os dados
                injetarEstadoNoSite();
            }

            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();

                // 2. Links internos (sgffrota://viagem...)
                if (url.startsWith("sgffrota:")) {
                    return true;
                }

                // 3. Sucesso no registro
                if (url.contains("/mobile-success")) {
                    String p = Uri.parse(url).getQueryParameter("placa");
                    if (p != null && !p.isEmpty()) {
                        placaAtual = p;
                        verificarPermissoes();
                    }
                    return true;
                }

                return false;
            }
        });

        // Garante que o modo=app está sendo passado
        webView.loadUrl(FORM_URL);
    }
    // Adicione este método na classe MainActivity
    private void injetarEstadoNoSite() {
        if (operacaoAtiva && !placaAtual.isEmpty()) {
            // O Android manda esse comando JS para o site
            String js = "javascript:(function() { " +
                    "document.getElementById('motorista').value = '" + placaAtual + " (Em Viagem)';" +
                    "document.getElementById('placa').value = '" + placaAtual + "';" +
                    "aplicarBloqueioDeCampos();" +
                    "const btn = document.getElementById('btnDesvincular');" +
                    "if(btn) {" +
                    "  btn.style.display = 'block';" +
                    "  btn.innerHTML = '<i class=\"fas fa-unlink\"></i> SAIR DA VIAGEM';" +
                    "}" +
                    "})()";

            webView.evaluateJavascript(js, null);
        }
    }
    private void verificarPermissoes() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        boolean todasOk = true;
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                todasOk = false; break;
            }
        }
        if (todasOk) iniciarOperacaoGPS();
        else ActivityCompat.requestPermissions(this, perms.toArray(new String[0]), 101);
    }

    private void iniciarOperacaoGPS() {
        salvarEstadoViagem(placaAtual);
        Intent intent = new Intent(this, LocationService.class);
        intent.putExtra("placa", placaAtual);
        ContextCompat.startForegroundService(this, intent);
        alternarTelaParaRastreamento();
    }

    private void alternarTelaParaRastreamento() {
        layoutFormulario.setVisibility(View.GONE);
        layoutRastreamento.setVisibility(View.VISIBLE);
        txtStatus.setText("Viagem: " + placaAtual);
    }

    private void abrirDialogoEncerramento() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Chegada - " + placaAtual);
        builder.setMessage("Digite o KM do painel:");
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        builder.setView(input);
        builder.setPositiveButton("FINALIZAR", (dialog, which) -> {
            String km = input.getText().toString();
            if (!km.isEmpty()) enviarDadosFinais(km);
        });
        builder.setNegativeButton("Cancelar", null);
        builder.show();
    }

    private void enviarDadosFinais(String kmChegada) {
        String url = SERVER_BASE + "/api/finalizar-viagem";
        JSONObject json = new JSONObject();
        try {
            if (placaAtual.isEmpty()) placaAtual = prefs.getString("placa_ativa", "");

            // LIMPEZA TOTAL: Remove tudo que não for letra ou número e põe em maiúsculo
            String placaLimpa = placaAtual.toUpperCase().trim();

            json.put("placa", placaLimpa);
            json.put("hodometro_chegada", Integer.parseInt(kmChegada));

            Log.d("DEBUG_FINALIZAR", "Enviando placa limpa: " + placaLimpa);
        } catch (Exception e) {
            Log.e("ERRO", "Erro no JSON: " + e.getMessage());
        }

        // ... resto do código do Volley

        RequestQueue queue = Volley.newRequestQueue(this);
        JsonObjectRequest req = new JsonObjectRequest(Request.Method.POST, url, json,
                response -> {
                    Toast.makeText(this, "Viagem Finalizada!", Toast.LENGTH_LONG).show();
                    resetarApp();
                },
                error -> {
                    String msg = "Erro ao finalizar.";
                    try {
                        if(error.networkResponse != null) msg = new String(error.networkResponse.data, "UTF-8");
                    } catch(Exception e){}
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                }
        );
        queue.add(req);
    }

    private void resetarApp() {
        stopService(new Intent(this, LocationService.class));
        limparEstadoViagem();
        layoutRastreamento.setVisibility(View.GONE);
        layoutFormulario.setVisibility(View.VISIBLE);
        webView.reload();
    }

    private void iniciarRelogio() {
        handler.post(new Runnable() {
            @Override public void run() {
                txtHora.setText(new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()));
                handler.postDelayed(this, 1000);
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int r, @NonNull String[] p, @NonNull int[] g) {
        super.onRequestPermissionsResult(r, p, g);
        if (r == 101 && g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) iniciarOperacaoGPS();
    }

    // Adicione esta classe dentro da MainActivity
    public class WebAppInterface {
        Context mContext;

        WebAppInterface(Context c) {
            mContext = c;
        }

        // Mantive o nome 'desvincular' para bater com seu JavaScript atual.
        @android.webkit.JavascriptInterface
        public void desvincular() {
            runOnUiThread(() -> {
                // 1. Limpa SharedPreferences com COMMIT e remove tudo
                SharedPreferences prefs = mContext.getSharedPreferences("DadosViagem", Context.MODE_PRIVATE);
                prefs.edit().clear().commit();

                // 2. Limpa Cookies e Cache do WebView (Isso mata os dados persistentes do site)
                android.webkit.CookieManager.getInstance().removeAllCookies(null);
                android.webkit.WebStorage.getInstance().deleteAllData();
                webView.clearCache(true);
                webView.clearFormData();

                // 3. Reseta variáveis
                operacaoAtiva = false;
                placaAtual = "";

                // 4. Recarrega para a tela de login
                webView.loadUrl(FORM_URL); // Recarrega a URL original limpa

                Toast.makeText(mContext, "Desconectado e limpo.", Toast.LENGTH_SHORT).show();
            });
        }
    }

}

