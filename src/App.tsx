import React, { useState, useEffect, useRef, useCallback, useLayoutEffect } from 'react';
import { Settings, Play, Pause, FolderOpen, RotateCcw, Clock, Hash, Download, Upload } from 'lucide-react';
import { motion, AnimatePresence } from 'framer-motion';
import { clsx } from 'clsx';

// Types
interface WordItem {
  id: string;
  text: string;
  file: string;
  lastSeen: number; // timestamp
  intervalIndex: number; // index in EBBINGHAUS_INTERVALS
  nextReview: number; // timestamp
  count: number; // times seen
}

interface AppSettings {
  frequency: number; // seconds
  showTime: boolean;
  showCount: boolean;
  mode: 'single' | 'full';
  selectedFile: string | null;
}

const EBBINGHAUS_INTERVALS = [
  5 * 60 * 1000,       // 5 min
  30 * 60 * 1000,      // 30 min
  12 * 60 * 60 * 1000, // 12 hours
  24 * 60 * 60 * 1000, // 1 day
  2 * 24 * 60 * 60 * 1000, // 2 days
  4 * 24 * 60 * 60 * 1000, // 4 days
  7 * 24 * 60 * 60 * 1000, // 7 days
  15 * 24 * 60 * 60 * 1000 // 15 days
];

export default function App() {
  const [words, setWords] = useState<WordItem[]>([]);
  const [currentWord, setCurrentWord] = useState<WordItem | null>(null);
  const [settings, setSettings] = useState<AppSettings>({
    frequency: 5,
    showTime: true,
    showCount: true,
    mode: 'full',
    selectedFile: null
  });
  const [isPlaying, setIsPlaying] = useState(false);
  const [currentTime, setCurrentTime] = useState(new Date());
  const [switchCount, setSwitchCount] = useState(0);
  const [showSettings, setShowSettings] = useState(false);
  const [files, setFiles] = useState<string[]>([]);
  
  // Timer for clock
  useEffect(() => {
    const timer = setInterval(() => setCurrentTime(new Date()), 1000);
    return () => clearInterval(timer);
  }, []);

  // Load saved state on mount
  useEffect(() => {
    const savedWords = localStorage.getItem('wordtosport_words');
    const savedSettings = localStorage.getItem('wordtosport_settings');
    const savedCount = localStorage.getItem('wordtosport_switch_count');
    
    if (savedWords) {
      const parsedWords = JSON.parse(savedWords);
      setWords(parsedWords);
      // Extract unique files
      const uniqueFiles = Array.from(new Set(parsedWords.map((w: WordItem) => w.file))) as string[];
      setFiles(uniqueFiles);
    }
    if (savedSettings) setSettings(JSON.parse(savedSettings));
    if (savedCount) setSwitchCount(parseInt(savedCount, 10));
  }, []);

  // Save state on change
  useEffect(() => {
    localStorage.setItem('wordtosport_words', JSON.stringify(words));
    localStorage.setItem('wordtosport_settings', JSON.stringify(settings));
    localStorage.setItem('wordtosport_switch_count', switchCount.toString());
  }, [words, settings, switchCount]);

  // Word switching logic
  const getNextWord = useCallback(() => {
    if (words.length === 0) return null;

    let candidates = words;
    if (settings.mode === 'single' && settings.selectedFile) {
      candidates = words.filter(w => w.file === settings.selectedFile);
    }

    if (candidates.length === 0) return null;

    const now = Date.now();
    // Prioritize words due for review
    const dueWords = candidates.filter(w => w.nextReview <= now);
    
    let next: WordItem;
    if (dueWords.length > 0) {
      // Pick random due word
      next = dueWords[Math.floor(Math.random() * dueWords.length)];
    } else {
      // Pick random word (or new word if we had logic for 'new')
      next = candidates[Math.floor(Math.random() * candidates.length)];
    }

    return next;
  }, [words, settings.mode, settings.selectedFile]);

  const updateWordProgress = (word: WordItem) => {
    const now = Date.now();
    const newIntervalIndex = Math.min(word.intervalIndex + 1, EBBINGHAUS_INTERVALS.length - 1);
    const nextReview = now + EBBINGHAUS_INTERVALS[newIntervalIndex];
    
    const updatedWord = {
      ...word,
      lastSeen: now,
      intervalIndex: newIntervalIndex,
      nextReview,
      count: word.count + 1
    };

    setWords(prev => prev.map(w => w.id === word.id ? updatedWord : w));
    return updatedWord;
  };

  useEffect(() => {
    let interval: NodeJS.Timeout;
    if (isPlaying && words.length > 0) {
      // Initial word if none selected
      if (!currentWord) {
        const next = getNextWord();
        if (next) {
          setCurrentWord(next);
          updateWordProgress(next);
          setSwitchCount(c => c + 1);
        }
      }

      interval = setInterval(() => {
        const next = getNextWord();
        if (next) {
          setCurrentWord(next);
          updateWordProgress(next);
          setSwitchCount(c => c + 1);
        }
      }, settings.frequency * 1000);
    }
    return () => clearInterval(interval);
  }, [isPlaying, words, settings.frequency, getNextWord]); // Removed currentWord from deps to avoid loop

  // File handling
  const handleFileSelect = async (e: React.ChangeEvent<HTMLInputElement>) => {
    if (!e.target.files) return;
    
    const newWords: WordItem[] = [];
    const newFiles = new Set(files);

    for (let i = 0; i < e.target.files.length; i++) {
      const file = e.target.files[i];
      // Accept txt files. Note: webkitdirectory might return non-txt files too.
      if (file.name.endsWith('.txt')) {
        const text = await file.text();
        // Split by full-width or half-width semicolon
        const rawWords = text.split(/[;；]/).map(s => s.trim()).filter(s => s.length > 0);
        
        rawWords.forEach(w => {
          // Check if word already exists to preserve progress
          const existing = words.find(existing => existing.text === w && existing.file === file.name);
          if (!existing) {
            newWords.push({
              id: `${file.name}-${w}-${Math.random().toString(36).substr(2, 9)}`,
              text: w,
              file: file.name,
              lastSeen: 0,
              intervalIndex: -1,
              nextReview: 0,
              count: 0
            });
          }
        });
        newFiles.add(file.name);
      }
    }

    if (newWords.length > 0) {
      setWords(prev => [...prev, ...newWords]);
      setFiles(Array.from(newFiles));
      // Auto-select first file if none selected
      if (!settings.selectedFile && newFiles.size > 0) {
        setSettings(s => ({ ...s, selectedFile: Array.from(newFiles)[0] }));
      }
    }
  };

  const clearProgress = () => {
    if (confirm('Are you sure you want to clear all progress and loaded words?')) {
      setWords([]);
      setFiles([]);
      setSwitchCount(0);
      setCurrentWord(null);
      setIsPlaying(false);
      localStorage.removeItem('wordtosport_words');
      localStorage.removeItem('wordtosport_settings');
      localStorage.removeItem('wordtosport_switch_count');
    }
  };

  const exportData = () => {
    const data = {
      words,
      settings,
      switchCount,
      timestamp: Date.now()
    };
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `wordtosport-backup-${new Date().toISOString().split('T')[0]}.json`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const importData = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    const reader = new FileReader();
    reader.onload = (event) => {
      try {
        const data = JSON.parse(event.target?.result as string);
        if (data.words && Array.isArray(data.words)) {
          setWords(data.words);
          const uniqueFiles = Array.from(new Set(data.words.map((w: WordItem) => w.file))) as string[];
          setFiles(uniqueFiles);
        }
        if (data.settings) setSettings(data.settings);
        if (data.switchCount) setSwitchCount(data.switchCount);
        alert('Data imported successfully!');
      } catch (err) {
        alert('Failed to import data. Invalid format.');
      }
    };
    reader.readAsText(file);
  };

  return (
    <div className="relative w-full h-full bg-black text-white flex flex-col items-center justify-center overflow-hidden">
      {/* Top Bar: Time */}
      {settings.showTime && (
        <div className="absolute top-4 right-6 text-xl font-mono opacity-70 flex items-center gap-2 z-10">
          <Clock size={16} />
          {currentTime.toLocaleTimeString()}
        </div>
      )}

      {/* Bottom Bar: Switch Count */}
      {settings.showCount && (
        <div className="absolute bottom-4 right-6 text-xl font-mono opacity-70 flex items-center gap-2 z-10">
          <Hash size={16} />
          {switchCount}
        </div>
      )}

      {/* Main Content: Word */}
      <div className="w-full px-4 flex items-center justify-center h-full">
        {currentWord ? (
          <AutoFitText text={currentWord.text} />
        ) : (
          <div className="text-gray-500 text-2xl text-center">
            {words.length === 0 ? "No words loaded. Open settings to add .txt files." : "Press Play to start."}
          </div>
        )}
      </div>

      {/* Controls Overlay (Hover or Toggle) */}
      <div className="absolute bottom-8 left-1/2 -translate-x-1/2 flex gap-4 opacity-0 hover:opacity-100 transition-opacity duration-300 p-4 bg-black/50 rounded-full backdrop-blur-sm z-20">
        <button 
          onClick={() => setIsPlaying(!isPlaying)}
          className="p-3 rounded-full bg-white text-black hover:bg-gray-200 transition-colors"
        >
          {isPlaying ? <Pause size={24} /> : <Play size={24} />}
        </button>
        <button 
          onClick={() => setShowSettings(true)}
          className="p-3 rounded-full bg-gray-800 text-white hover:bg-gray-700 transition-colors"
        >
          <Settings size={24} />
        </button>
      </div>

      {/* Settings Modal */}
      <AnimatePresence>
        {showSettings && (
          <motion.div 
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            className="fixed inset-0 bg-black/80 backdrop-blur-sm z-50 flex items-center justify-center p-4"
            onClick={(e) => {
              if (e.target === e.currentTarget) setShowSettings(false);
            }}
          >
            <motion.div 
              initial={{ scale: 0.9, opacity: 0 }}
              animate={{ scale: 1, opacity: 1 }}
              exit={{ scale: 0.9, opacity: 0 }}
              className="bg-zinc-900 border border-zinc-800 rounded-2xl p-6 w-full max-w-md shadow-2xl max-h-[90vh] overflow-y-auto"
            >
              <h2 className="text-2xl font-bold mb-6 flex items-center gap-2">
                <Settings className="text-emerald-500" /> Settings
              </h2>

              <div className="space-y-6">
                {/* File Import */}
                <div className="space-y-2">
                  <label className="block text-sm font-medium text-gray-400">Import .txt Files</label>
                  <label className="flex items-center justify-center w-full p-4 border-2 border-dashed border-zinc-700 rounded-xl hover:border-emerald-500 hover:bg-zinc-800 transition-colors cursor-pointer group">
                    <div className="flex flex-col items-center gap-2 text-gray-400 group-hover:text-white">
                      <FolderOpen size={24} />
                      <span className="text-sm">Select Folder or Files</span>
                    </div>
                    <input 
                      type="file" 
                      multiple 
                      // @ts-ignore
                      webkitdirectory=""
                      directory=""
                      onChange={handleFileSelect} 
                      className="hidden" 
                    />
                  </label>
                  <p className="text-xs text-gray-500 text-center">
                    {words.length} words loaded from {files.length} files.
                  </p>
                </div>

                {/* Frequency */}
                <div className="space-y-2">
                  <label className="block text-sm font-medium text-gray-400">
                    Switch Frequency: <span className="text-white">{settings.frequency}s</span>
                  </label>
                  <input 
                    type="range" 
                    min="1" 
                    max="60" 
                    value={settings.frequency} 
                    onChange={(e) => setSettings(s => ({ ...s, frequency: Number(e.target.value) }))}
                    className="w-full accent-emerald-500"
                  />
                </div>

                {/* Mode Selection */}
                <div className="space-y-2">
                  <label className="block text-sm font-medium text-gray-400">Mode</label>
                  <div className="flex gap-2 p-1 bg-zinc-800 rounded-lg">
                    <button 
                      onClick={() => setSettings(s => ({ ...s, mode: 'full' }))}
                      className={clsx(
                        "flex-1 py-2 text-sm rounded-md transition-all",
                        settings.mode === 'full' ? "bg-emerald-600 text-white shadow-sm" : "text-gray-400 hover:text-white"
                      )}
                    >
                      Full Record
                    </button>
                    <button 
                      onClick={() => setSettings(s => ({ ...s, mode: 'single' }))}
                      className={clsx(
                        "flex-1 py-2 text-sm rounded-md transition-all",
                        settings.mode === 'single' ? "bg-emerald-600 text-white shadow-sm" : "text-gray-400 hover:text-white"
                      )}
                    >
                      Single File
                    </button>
                  </div>
                </div>

                {/* File Selection (Single Mode) */}
                {settings.mode === 'single' && (
                  <div className="space-y-2">
                    <label className="block text-sm font-medium text-gray-400">Select File</label>
                    <select 
                      value={settings.selectedFile || ''} 
                      onChange={(e) => setSettings(s => ({ ...s, selectedFile: e.target.value }))}
                      className="w-full bg-zinc-800 border border-zinc-700 rounded-lg p-2 text-white focus:outline-none focus:ring-2 focus:ring-emerald-500"
                    >
                      {files.map(f => (
                        <option key={f} value={f}>{f}</option>
                      ))}
                    </select>
                  </div>
                )}

                {/* Toggles */}
                <div className="flex justify-between gap-4">
                  <label className="flex items-center gap-2 cursor-pointer">
                    <input 
                      type="checkbox" 
                      checked={settings.showTime} 
                      onChange={(e) => setSettings(s => ({ ...s, showTime: e.target.checked }))}
                      className="w-4 h-4 accent-emerald-500"
                    />
                    <span className="text-sm text-gray-300">Show Time</span>
                  </label>
                  <label className="flex items-center gap-2 cursor-pointer">
                    <input 
                      type="checkbox" 
                      checked={settings.showCount} 
                      onChange={(e) => setSettings(s => ({ ...s, showCount: e.target.checked }))}
                      className="w-4 h-4 accent-emerald-500"
                    />
                    <span className="text-sm text-gray-300">Show Count</span>
                  </label>
                </div>

                {/* Data Management */}
                <div className="pt-4 border-t border-zinc-800 space-y-2">
                  <label className="block text-sm font-medium text-gray-400">Data Management</label>
                  <div className="flex gap-2">
                    <button 
                      onClick={exportData}
                      className="flex-1 py-2 bg-zinc-800 hover:bg-zinc-700 rounded-lg text-sm flex items-center justify-center gap-2 transition-colors"
                    >
                      <Download size={16} /> Export
                    </button>
                    <label className="flex-1 py-2 bg-zinc-800 hover:bg-zinc-700 rounded-lg text-sm flex items-center justify-center gap-2 transition-colors cursor-pointer">
                      <Upload size={16} /> Import
                      <input type="file" accept=".json" onChange={importData} className="hidden" />
                    </label>
                  </div>
                  <button 
                    onClick={clearProgress}
                    className="w-full py-2 text-red-400 hover:text-red-300 hover:bg-red-900/20 rounded-lg transition-colors flex items-center justify-center gap-2 text-sm"
                  >
                    <RotateCcw size={16} /> Reset All
                  </button>
                </div>
              </div>

              <button 
                onClick={() => setShowSettings(false)}
                className="absolute top-4 right-4 text-gray-500 hover:text-white"
              >
                ✕
              </button>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}

// Auto-scaling text component using SVG
const AutoFitText = ({ text }: { text: string }) => {
  const svgRef = useRef<SVGSVGElement>(null);
  const textRef = useRef<SVGTextElement>(null);
  const [viewBox, setViewBox] = useState("0 0 100 20");
  const [opacity, setOpacity] = useState(0);

  useLayoutEffect(() => {
    if (textRef.current) {
      const bbox = textRef.current.getBBox();
      // Add some padding (e.g., 10% horizontal, 20% vertical)
      const paddingX = bbox.width * 0.1;
      const paddingY = bbox.height * 0.2;
      
      // Ensure min size to avoid division by zero or weird scales
      const width = Math.max(bbox.width + paddingX, 10);
      const height = Math.max(bbox.height + paddingY, 10);
      const x = bbox.x - paddingX / 2;
      const y = bbox.y - paddingY / 2;

      setViewBox(`${x} ${y} ${width} ${height}`);
      setOpacity(1);
    }
  }, [text]);

  return (
    <div className="w-full h-full flex items-center justify-center p-4">
      <svg 
        ref={svgRef}
        viewBox={viewBox} 
        className="w-full max-h-full transition-opacity duration-300"
        style={{ opacity }}
        preserveAspectRatio="xMidYMid meet"
      >
        <text 
          ref={textRef}
          x="50%" 
          y="50%" 
          textAnchor="middle" 
          dominantBaseline="middle" 
          fill="white"
          className="font-sans font-bold"
          fontSize="20" // Base size, will be scaled by viewBox
        >
          {text}
        </text>
      </svg>
    </div>
  );
};
