package com.cl.wordtosport;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;

import java.io.IOException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import com.cl.wordtosport.ToneGenerator;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "WordToSport";
    private static final String PREFS_NAME = "WordPrefs";
    private static final String KEY_FOLDER_URI = "folder_uri";
    private static final String KEY_CURRENT_FILE = "current_file";
    private static final String KEY_MODE = "mode"; // 0: Single, 1: All
    private static final String KEY_TEMPO = "tempo"; // BPM (beats per minute)
    private static final String KEY_SHOW_TIME = "show_time";
    private static final String KEY_SHOW_COUNT = "show_count";
    private static final String KEY_HISTORY_DATA = "history_data";
    private static final String KEY_SOUND_ENABLED = "sound_enabled";

    // Ebbinghaus intervals in seconds: 5m, 30m, 12h, 1d, 2d, 4d, 7d, 15d
    private static final long[] INTERVALS = {
            5 * 60,
            30 * 60,
            12 * 3600,
            24 * 3600,
            2 * 24 * 3600,
            4 * 24 * 3600,
            7 * 24 * 3600,
            15 * 24 * 3600
    };

    private TextView tvWord;
    private TextView tvTime;
    private TextView tvCount;
    private FrameLayout rootLayout;
    private View settingsOverlay;

    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable wordSwitcher;
    private Runnable timeUpdater;
    private Runnable metronomeRunnable; // For metronome beat

    private List<WordItem> allWords = new ArrayList<>();
    private List<WordItem> currentFileWords = new ArrayList<>();
    private Map<String, WordState> wordStates = new HashMap<>(); // Key: word text

    private Uri folderUri;
    private String currentFileName;
    private int mode = 0; // 0: Single, 1: All
    private int tempo = 60; // Beats per minute (BPM)
    private boolean soundEnabled = true; // Whether to play metronome sound
    private boolean showTime = true;
    private boolean showCount = true;
    private int switchCount = 0;

    private Random random = new Random();
    
    // Sound variables
    private SoundPool soundPool;
    private int clickSoundId;
    private boolean soundPoolReady = false;

    private static final int FOLDER_PICKER_REQUEST_CODE = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);

        tvWord = findViewById(R.id.tvWord);
        tvTime = findViewById(R.id.tvTime);
        tvCount = findViewById(R.id.tvCount);
        rootLayout = findViewById(R.id.rootLayout);

        // Initialize sound system
        initializeSound();

        // Load Settings
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String uriStr = prefs.getString(KEY_FOLDER_URI, null);
        if (uriStr != null) folderUri = Uri.parse(uriStr);
        currentFileName = prefs.getString(KEY_CURRENT_FILE, null);
        mode = prefs.getInt(KEY_MODE, 0);
        tempo = prefs.getInt(KEY_TEMPO, 60); // Default to 60 BPM
        soundEnabled = prefs.getBoolean(KEY_SOUND_ENABLED, true);
        showTime = prefs.getBoolean(KEY_SHOW_TIME, true);
        showCount = prefs.getBoolean(KEY_SHOW_COUNT, true);

        loadWordStates();

        updateVisibility();

        // Setup double tap for settings
        rootLayout.setOnClickListener(new View.OnClickListener() {
            long lastClickTime = 0;
            @Override
            public void onClick(View v) {
                long now = System.currentTimeMillis();
                if (now - lastClickTime < 500) {
                    showSettingsDialog();
                }
                lastClickTime = now;
            }
        });

        // Initial full screen setup
        hideSystemUI();

        startTimers();

        if (folderUri != null) {
            loadFilesFromFolder(folderUri);
        } else {
            tvWord.setText("Double tap to select folder");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == FOLDER_PICKER_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (SecurityException e) {
                    Log.w(TAG, "Failed to take permission: " + e.getMessage());
                }
                saveFolderUri(uri);
                loadFilesFromFolder(uri);
            }
        }
    }

    private void startTimers() {
        // Time Updater
        timeUpdater = new Runnable() {
            @Override
            public void run() {
                if (showTime) {
                    SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
                    tvTime.setText(sdf.format(new Date()));
                }
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(timeUpdater);

        // Metronome Beat
        startMetronome();
    }
    
    private void startMetronome() {
        if (metronomeRunnable != null) {
            handler.removeCallbacks(metronomeRunnable);
        }
        
        metronomeRunnable = new Runnable() {
            @Override
            public void run() {
                // Play metronome sound
                playClickSound();
                
                // Switch word on each beat
                switchWord();
                
                // Schedule next beat based on tempo (BPM)
                // Convert BPM to milliseconds: 60 seconds / BPM * 1000 ms
                long interval = (60 * 1000) / tempo;
                handler.postDelayed(this, interval);
            }
        };
        
        // Start the metronome
        long initialInterval = (60 * 1000) / tempo;
        handler.postDelayed(metronomeRunnable, initialInterval);
    }

    private void switchWord() {
        List<WordItem> candidates = (mode == 0) ? currentFileWords : allWords;

        if (candidates.isEmpty()) {
            if (folderUri == null) {
                tvWord.setText("Double tap to setup");
            } else {
                tvWord.setText("No words found");
            }
            return;
        }

        // Ebbinghaus Logic
        WordItem selected = pickNextWord(candidates);
        
        if (selected != null) {
            tvWord.setText(selected.text);
            
            // Update State
            WordState state = wordStates.get(selected.text);
            if (state == null) {
                state = new WordState();
                wordStates.put(selected.text, state);
            }
            state.lastSeen = System.currentTimeMillis();
            if (state.level < INTERVALS.length - 1) {
                state.level++;
            }
            
            switchCount++;
            if (showCount) {
                tvCount.setText("Count: " + switchCount);
            }
            
            saveWordStates(); // Persist occasionally? For now every time to be safe
        }
    }

    private WordItem pickNextWord(List<WordItem> candidates) {
        long now = System.currentTimeMillis();
        List<WordItem> dueWords = new ArrayList<>();
        List<WordItem> newWords = new ArrayList<>();

        for (WordItem item : candidates) {
            WordState state = wordStates.get(item.text);
            if (state == null) {
                newWords.add(item);
            } else {
                long interval = INTERVALS[Math.min(state.level, INTERVALS.length - 1)] * 1000L;
                if (now - state.lastSeen > interval) {
                    dueWords.add(item);
                }
            }
        }

        if (!dueWords.isEmpty()) {
            // Pick random due word
            return dueWords.get(random.nextInt(dueWords.size()));
        } else if (!newWords.isEmpty()) {
            // Pick random new word
            return newWords.get(random.nextInt(newWords.size()));
        } else {
            // All reviewed recently, pick random from all
            return candidates.get(random.nextInt(candidates.size()));
        }
    }

    private void loadFilesFromFolder(Uri uri) {
        // In a real app, this should be async
        new Thread(() -> {
            allWords.clear();
            currentFileWords.clear();

            DocumentFile dir = DocumentFile.fromTreeUri(this, uri);
            if (dir != null && dir.isDirectory()) {
                for (DocumentFile file : dir.listFiles()) {
                    if (file.getName() != null && file.getName().endsWith(".txt")) {
                        List<WordItem> fileWords = parseFile(file);
                        allWords.addAll(fileWords);
                        if (currentFileName != null && file.getName().equals(currentFileName)) {
                            currentFileWords.addAll(fileWords);
                        }
                    }
                }
            }
            
            // If current file not found or not set, default to first
            if (currentFileWords.isEmpty() && !allWords.isEmpty()) {
                 // Try to find a file
                 if (dir != null) {
                     for (DocumentFile file : dir.listFiles()) {
                         if (file.getName().endsWith(".txt")) {
                             currentFileName = file.getName();
                             getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                     .edit().putString(KEY_CURRENT_FILE, currentFileName).apply();
                             currentFileWords.addAll(parseFile(file));
                             break;
                         }
                     }
                 }
            }

            runOnUiThread(() -> {
                Toast.makeText(this, "Loaded " + allWords.size() + " words", Toast.LENGTH_SHORT).show();
                switchWord(); // Show first word immediately
            });
        }).start();
    }

    private List<WordItem> parseFile(DocumentFile file) {
        List<WordItem> list = new ArrayList<>();
        try {
            InputStream is = getContentResolver().openInputStream(file.getUri());
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n"); // Preserve line breaks
            }
            reader.close();
            
            // Split by ; or ； or \n (newlines)
            String content = sb.toString();
            String[] tokens = content.split("[;；\\n\\r]+");
            for (String token : tokens) {
                if (!token.trim().isEmpty()) {
                    list.add(new WordItem(token.trim(), file.getName()));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing file", e);
        }
        return list;
    }

    private void showSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Settings");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 20, 50, 20);

        // Folder Selection
        Button btnFolder = new Button(this);
        btnFolder.setText("Select Folder");
        btnFolder.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            startActivityForResult(intent, FOLDER_PICKER_REQUEST_CODE);
        });
        layout.addView(btnFolder);

        // File Selection (Spinner)
        TextView tvFile = new TextView(this);
        tvFile.setText("Current File:");
        layout.addView(tvFile);
        
        Spinner spinnerFile = new Spinner(this);
        List<String> fileNames = new ArrayList<>();
        if (folderUri != null) {
            DocumentFile dir = DocumentFile.fromTreeUri(this, folderUri);
            if (dir != null) {
                for (DocumentFile f : dir.listFiles()) {
                    if (f.getName().endsWith(".txt")) fileNames.add(f.getName());
                }
            }
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, fileNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerFile.setAdapter(adapter);
        if (currentFileName != null) {
            int pos = fileNames.indexOf(currentFileName);
            if (pos >= 0) spinnerFile.setSelection(pos);
        }
        layout.addView(spinnerFile);

        // Mode Selection
        TextView tvMode = new TextView(this);
        tvMode.setText("Mode:");
        layout.addView(tvMode);
        
        Spinner spinnerMode = new Spinner(this);
        String[] modes = {"Single File", "All Records"};
        ArrayAdapter<String> modeAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, modes);
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerMode.setAdapter(modeAdapter);
        spinnerMode.setSelection(mode);
        layout.addView(spinnerMode);

        // Tempo (Metronome)
        TextView tvTempo = new TextView(this);
        tvTempo.setText("Tempo: " + tempo + " BPM");
        layout.addView(tvTempo);
        
        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(240); // Max 240 BPM
        seekBar.setProgress(tempo);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (progress < 1) progress = 1;
                tvTempo.setText("Tempo: " + progress + " BPM");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        layout.addView(seekBar);

        // Toggles
        CheckBox cbSound = new CheckBox(this);
        cbSound.setText("Sound Enabled");
        cbSound.setChecked(soundEnabled);
        layout.addView(cbSound);

        CheckBox cbTime = new CheckBox(this);
        cbTime.setText("Show Time");
        cbTime.setChecked(showTime);
        layout.addView(cbTime);

        CheckBox cbCount = new CheckBox(this);
        cbCount.setText("Show Count");
        cbCount.setChecked(showCount);
        layout.addView(cbCount);

        builder.setView(layout);

        builder.setPositiveButton("Save", (dialog, which) -> {
            // Save Settings
            tempo = Math.max(1, seekBar.getProgress()); // Tempo in BPM
            soundEnabled = cbSound.isChecked();
            showTime = cbTime.isChecked();
            showCount = cbCount.isChecked();
            mode = spinnerMode.getSelectedItemPosition();
            if (spinnerFile.getSelectedItem() != null) {
                currentFileName = spinnerFile.getSelectedItem().toString();
            }

            SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
            editor.putInt(KEY_TEMPO, tempo);
            editor.putBoolean(KEY_SOUND_ENABLED, soundEnabled);
            editor.putBoolean(KEY_SHOW_TIME, showTime);
            editor.putBoolean(KEY_SHOW_COUNT, showCount);
            editor.putInt(KEY_MODE, mode);
            editor.putString(KEY_CURRENT_FILE, currentFileName);
            editor.apply();

            updateVisibility();
            
            // Reload words if file changed
            if (folderUri != null) loadFilesFromFolder(folderUri);
            
            // Restart metronome with new tempo
            handler.removeCallbacks(metronomeRunnable);
            startMetronome();
        });

        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void updateVisibility() {
        tvTime.setVisibility(showTime ? View.VISIBLE : View.GONE);
        tvCount.setVisibility(showCount ? View.VISIBLE : View.GONE);
    }

    private void saveFolderUri(Uri uri) {
        folderUri = uri;
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_FOLDER_URI, uri.toString())
                .apply();
    }

    private void loadWordStates() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_HISTORY_DATA, "{}");
        try {
            JSONObject obj = new JSONObject(json);
            JSONArray names = obj.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String key = names.getString(i);
                    JSONObject val = obj.getJSONObject(key);
                    WordState state = new WordState();
                    state.lastSeen = val.optLong("lastSeen", 0);
                    state.level = val.optInt("level", 0);
                    wordStates.put(key, state);
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }
    
    private void saveWordStates() {
        JSONObject obj = new JSONObject();
        try {
            for (Map.Entry<String, WordState> entry : wordStates.entrySet()) {
                JSONObject val = new JSONObject();
                val.put("lastSeen", entry.getValue().lastSeen);
                val.put("level", entry.getValue().level);
                obj.put(entry.getKey(), val);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_HISTORY_DATA, obj.toString())
                .apply();
    }
    
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // Delay to ensure UI is fully loaded before hiding system bars
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    hideSystemUI();
                }
            }, 100);
        }
    }

    private void initializeSound() {
        // Initialize SoundPool for Android API 21+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            
            soundPool = new SoundPool.Builder()
                    .setMaxStreams(1)
                    .setAudioAttributes(audioAttributes)
                    .build();
        } else {
            // For older versions
            soundPool = new SoundPool(1, AudioManager.STREAM_MUSIC, 0);
        }
        
        // Generate a simple click sound using audio tone generation
        // This is a basic implementation - in a real app, you'd load a sound file
        generateClickSound();
    }
    
    private void generateClickSound() {
        // In a real implementation, you would load an actual sound file from res/raw
        // For now, we'll just mark the sound system as ready
        soundPoolReady = true;
    }
    
    private void playClickSound() {
        if (soundEnabled && soundPoolReady) {
            // Play a short beep sound
            new Thread(new Runnable() {
                @Override
                public void run() {
                    ToneGenerator.playTone(100); // 100ms beep
                }
            }).start();
            
            // For visual feedback, briefly change the background color with animation
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    // Create a subtle pulsing effect
                    rootLayout.animate()
                        .alpha(0.8f)
                        .setDuration(50)
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                rootLayout.animate()
                                    .alpha(1.0f)
                                    .setDuration(100)
                                    .start();
                            }
                        })
                        .start();
                }
            });
        }
    }
    
    private void hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            final Window window = getWindow();
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                // Hide status bar and navigation bar
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                // Allow bars to show when user swipes from edge
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                
                // Make content draw behind the status bar and navigation bar
                window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
                window.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
            }
        } else {
            // Legacy approach
            View decorView = getWindow().getDecorView();
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    // Helper Classes
    private static class WordItem {
        String text;
        String sourceFile;
        WordItem(String text, String sourceFile) {
            this.text = text;
            this.sourceFile = sourceFile;
        }
    }

    private static class WordState {
        long lastSeen;
        int level;
    }
}