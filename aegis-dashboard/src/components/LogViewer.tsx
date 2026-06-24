export default function LogViewer({ activeStream }: { activeStream: string }) {
  return (
    <div className="h-full bg-black border border-gray-700 rounded-b-lg p-4 font-mono text-sm overflow-y-auto">
      {activeStream ? (
        <div className="text-green-400">Streaming logs for: {activeStream}...</div>
      ) : (
        <div className="text-gray-500">No active stream selected.</div>
      )}
    </div>
  );
}
