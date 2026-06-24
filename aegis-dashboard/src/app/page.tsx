"use client";

import { useState } from "react";
import TopologyMatrix from "@/components/TopologyMatrix";
import CommandCenter from "@/components/CommandCenter";
import LogViewer from "@/components/LogViewer";

export default function Dashboard() {
  const [watchId, setWatchId] = useState("");
  const [activeStream, setActiveStream] = useState("");

  const handleStreamClick = () => {
    setActiveStream(watchId);
  };

  return (
    <div className="min-h-screen bg-black text-gray-200 p-6 font-sans">
      {/* Header */}
      <header className="flex items-center justify-between mb-8 pb-4 border-b border-gray-800">
        <h1 className="text-3xl font-bold text-white tracking-tight">
          AegisOS Control Plane <span className="text-gray-500 text-lg">v2.0.0</span>
        </h1>
        <div className="flex items-center space-x-3">
          <div className="w-3 h-3 bg-green-500 rounded-full animate-pulse"></div>
          <span className="text-sm font-medium text-green-400">Cluster Online</span>
        </div>
      </header>

      {/* Main Grid */}
      <div className="grid grid-cols-1 xl:grid-cols-3 gap-6">
        {/* Left Column */}
        <div className="xl:col-span-1 flex flex-col space-y-6">
          <CommandCenter />
          <TopologyMatrix />
        </div>

        {/* Right Column (Telemetry Container) */}
        <div className="xl:col-span-2 flex flex-col h-[800px] bg-gray-900 border border-gray-700 rounded-lg shadow-xl overflow-hidden">
          {/* Telemetry Header */}
          <div className="flex items-center justify-between bg-gray-800 p-4 border-b border-gray-700">
            <h2 className="text-lg font-semibold text-white">Live Telemetry</h2>
            <div className="flex items-center space-x-3">
              <input
                type="text"
                placeholder="Enter Node ID or Job ID..."
                value={watchId}
                onChange={(e) => setWatchId(e.target.value)}
                className="bg-gray-900 border border-gray-600 text-white text-sm rounded px-3 py-1.5 focus:outline-none focus:border-blue-500 focus:ring-1 focus:ring-blue-500 w-64 placeholder-gray-500"
              />
              <button
                onClick={handleStreamClick}
                className="bg-blue-600 hover:bg-blue-500 text-white text-sm font-semibold px-4 py-1.5 rounded transition-colors"
              >
                STREAM
              </button>
            </div>
          </div>

          {/* Telemetry Body (LogViewer) */}
          <div className="flex-1 overflow-hidden">
            <LogViewer activeStream={activeStream} />
          </div>
        </div>
      </div>
    </div>
  );
}
