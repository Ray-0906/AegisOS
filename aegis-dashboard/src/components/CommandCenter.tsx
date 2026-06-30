"use client";

import { useState } from "react";

export default function CommandCenter() {
  const [artifactId, setArtifactId] = useState("");
  const [command, setCommand] = useState("");
  const [pipeTo, setPipeTo] = useState("");
  
  const [cpuCores, setCpuCores] = useState(1);
  const [memoryMb, setMemoryMb] = useState(256);
  const [requireGpu, setRequireGpu] = useState(false);
  const [targetNodeId, setTargetNodeId] = useState("");
  const [antiAffinityId, setAntiAffinityId] = useState("");
  
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState("");

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setMessage("");
    
    try {
      const payload = {
        artifactId: artifactId,
        executionCommand: command,
        pipeToProcessId: pipeTo || null,
        resourceConstraints: {
          requiredCpuCores: cpuCores,
          requiredMemoryMb: memoryMb,
          requireGpu: requireGpu
        },
        placementConstraints: {
          targetNodeId: targetNodeId || null,
          antiAffinityProcessId: antiAffinityId || null
        }
      };

      const res = await fetch("http://localhost:18000/v1/processes", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });

      if (!res.ok) {
        throw new Error(`Submit failed: ${res.statusText}`);
      }

      const data = await res.json();
      setMessage(`Success! Job ID: ${data.jobId || data.processId || data}`);
    } catch (err: any) {
      setMessage(`Error: ${err.message}`);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="bg-zinc-900/50 border border-zinc-800 rounded-lg p-6 font-mono text-zinc-300 w-full">
      <h2 className="text-xl mb-6 tracking-widest text-zinc-100 uppercase border-b border-zinc-800 pb-4">Command Center</h2>
      <form onSubmit={handleSubmit}>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-8">
          <div>
            <h3 className="text-sm text-cyan-400 mb-4 tracking-widest uppercase">Execution Details</h3>
            <div className="space-y-4">
              <div>
                <label className="block text-[10px] text-zinc-500 uppercase tracking-widest mb-2">Artifact ID</label>
                <input type="text" value={artifactId} onChange={e => setArtifactId(e.target.value)} required
                  className="bg-black text-cyan-400 border border-zinc-700 outline-none focus:border-cyan-400 p-2 w-full transition-colors" />
              </div>
              <div>
                <label className="block text-[10px] text-zinc-500 uppercase tracking-widest mb-2">Command</label>
                <input type="text" value={command} onChange={e => setCommand(e.target.value)} required
                  className="bg-black text-cyan-400 border border-zinc-700 outline-none focus:border-cyan-400 p-2 w-full transition-colors" />
              </div>
              <div>
                <label className="block text-[10px] text-zinc-500 uppercase tracking-widest mb-2">Pipe To Process ID (Optional)</label>
                <input type="text" value={pipeTo} onChange={e => setPipeTo(e.target.value)}
                  className="bg-black text-cyan-400 border border-zinc-700 outline-none focus:border-cyan-400 p-2 w-full transition-colors" />
              </div>
            </div>
          </div>
          
          <div>
            <h3 className="text-sm text-cyan-400 mb-4 tracking-widest uppercase">Scheduling Constraints</h3>
            <div className="space-y-4">
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-[10px] text-zinc-500 uppercase tracking-widest mb-2">CPU Cores</label>
                  <input type="number" min="1" value={cpuCores} onChange={e => setCpuCores(parseInt(e.target.value) || 1)} required
                    className="bg-black text-cyan-400 border border-zinc-700 outline-none focus:border-cyan-400 p-2 w-full transition-colors" />
                </div>
                <div>
                  <label className="block text-[10px] text-zinc-500 uppercase tracking-widest mb-2">Memory (MB)</label>
                  <input type="number" min="1" value={memoryMb} onChange={e => setMemoryMb(parseInt(e.target.value) || 256)} required
                    className="bg-black text-cyan-400 border border-zinc-700 outline-none focus:border-cyan-400 p-2 w-full transition-colors" />
                </div>
              </div>
              
              <div>
                <label className="flex items-center space-x-2 cursor-pointer mt-2">
                  <input type="checkbox" checked={requireGpu} onChange={e => setRequireGpu(e.target.checked)} 
                    className="bg-black border border-zinc-700 focus:border-cyan-400" />
                  <span className="text-[10px] text-zinc-500 uppercase tracking-widest">Require GPU</span>
                </label>
              </div>
              
              <div>
                <label className="block text-[10px] text-zinc-500 uppercase tracking-widest mb-2">Target Node ID (Optional)</label>
                <input type="text" value={targetNodeId} onChange={e => setTargetNodeId(e.target.value)} placeholder="Hex string"
                  className="bg-black text-cyan-400 border border-zinc-700 outline-none focus:border-cyan-400 p-2 w-full transition-colors" />
              </div>
              
              <div>
                <label className="block text-[10px] text-zinc-500 uppercase tracking-widest mb-2">Anti-Affinity Process ID (Optional)</label>
                <input type="text" value={antiAffinityId} onChange={e => setAntiAffinityId(e.target.value)}
                  className="bg-black text-cyan-400 border border-zinc-700 outline-none focus:border-cyan-400 p-2 w-full transition-colors" />
              </div>
            </div>
          </div>
        </div>
        
        <button type="submit" disabled={loading}
          className="bg-cyan-950/30 text-cyan-400 border border-cyan-400 hover:bg-cyan-400/20 transition-all uppercase tracking-widest py-2 px-4 w-full mt-6">
          {loading ? "Submitting..." : "Submit Job"}
        </button>
        {message && (
          <div className={`mt-4 p-3 bg-black border border-zinc-800 text-sm break-all ${message.startsWith("Error") ? "text-rose-400" : "text-emerald-400"}`}>
            {message}
          </div>
        )}
      </form>
    </div>
  );
}
