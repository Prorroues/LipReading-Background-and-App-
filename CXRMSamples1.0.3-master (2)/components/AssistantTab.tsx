import React, { useState } from 'react';
import { HelpCircle, Plus, Settings, Wifi, Glasses, Check, Pencil, Battery } from 'lucide-react';
import { ChatMessage } from '../types';

const AssistantTab: React.FC = () => {
  // Device state
  const [deviceName, setDeviceName] = useState('Glasses_0919');
  const [isEditingName, setIsEditingName] = useState(false);
  const [isConnected, setIsConnected] = useState(false);
  const [batteryLevel] = useState(85); // Simulated battery level

  // Clear messages for the initial state
  const messages: ChatMessage[] = [];

  const handleToggleConnect = () => {
    setIsConnected(!isConnected);
  };

  return (
    <div className="flex flex-col h-full bg-gradient-to-b from-teal-50 to-gray-50">
      {/* Header */}
      <header className="flex justify-between items-start p-4 pt-8 bg-transparent sticky top-0 z-10 backdrop-blur-sm">
        <div className="flex-1 mr-4">
          <div className="flex items-center group">
            {isEditingName ? (
              <div className="flex items-center bg-white/50 rounded-lg px-2 border border-teal-200">
                <input
                  autoFocus
                  type="text"
                  value={deviceName}
                  onChange={(e) => setDeviceName(e.target.value)}
                  onBlur={() => setIsEditingName(false)}
                  onKeyDown={(e) => e.key === 'Enter' && setIsEditingName(false)}
                  className="text-2xl font-semibold text-gray-800 bg-transparent outline-none w-full max-w-[200px]"
                />
                <button onClick={() => setIsEditingName(false)} className="text-teal-600 ml-1">
                  <Check size={20} />
                </button>
              </div>
            ) : (
              <div 
                className="flex items-center cursor-pointer group"
                onClick={() => setIsEditingName(true)}
              >
                <h1 className="text-2xl font-semibold text-gray-800 transition-colors group-hover:text-teal-700">
                  {deviceName || '未命名设备'}
                </h1>
                <Pencil size={14} className="ml-2 text-gray-400 opacity-0 group-hover:opacity-100 transition-opacity" />
              </div>
            )}
          </div>
          
          <div className="flex items-center mt-3 space-x-4">
             {/* Glasses Status Icon */}
             <button 
                onClick={handleToggleConnect}
                className={`flex items-center space-x-1.5 transition-colors ${isConnected ? 'text-green-600' : 'text-gray-400'}`}
             >
                <Glasses size={20} strokeWidth={isConnected ? 2.5 : 1.5} />
                <span className="text-xs font-bold uppercase tracking-wider">
                    {isConnected ? '已连接' : '未连接'}
                </span>
             </button>
             
             {/* Network and Battery Status */}
             <div className="flex items-center space-x-3">
                {/* Wifi Icon - Changes to Green when connected */}
                <div className={`${isConnected ? 'text-green-600' : 'text-gray-300'}`}>
                    <Wifi size={18} strokeWidth={2.5} />
                </div>
                
                {/* Battery Icon and Percentage - Changes to Green when connected */}
                <div className={`flex items-center space-x-1 ${isConnected ? 'text-green-600' : 'text-gray-300'}`}>
                    <Battery 
                      size={16} 
                      className={isConnected && batteryLevel < 20 ? 'text-red-500' : ''} 
                    />
                    <span className="text-xs font-semibold">{batteryLevel}%</span>
                </div>
             </div>
          </div>
        </div>
        
        <div className="flex space-x-3 text-gray-500 mt-1">
          <button className="p-2 hover:bg-white/50 rounded-full transition-colors">
            <HelpCircle size={22} />
          </button>
          <button className="p-2 hover:bg-white/50 rounded-full transition-colors">
            <Plus size={22} />
          </button>
          <button className="p-2 hover:bg-white/50 rounded-full transition-colors">
            <Settings size={22} />
          </button>
        </div>
      </header>

      {/* Chat Area */}
      <div className="flex-1 overflow-y-auto p-4 pb-24 space-y-6 flex flex-col items-center justify-center">
        {messages.length > 0 ? (
          <div className="w-full space-y-6">
            {messages.map((msg, index) => (
              <div key={msg.id} className="flex flex-col w-full">
                {msg.timestamp && (index === 0 || messages[index - 1].timestamp !== msg.timestamp) && (
                  <div className="text-center text-xs text-gray-400 mb-4">{msg.timestamp}</div>
                )}
                
                <div className={`flex w-full ${msg.sender === 'user' ? 'justify-end' : 'justify-start'}`}>
                    {msg.sender === 'system' && (
                        <div className="w-10 h-10 rounded-full bg-cyan-400 flex items-center justify-center text-white font-bold mr-3 shadow-sm flex-shrink-0 text-sm">
                            AI
                        </div>
                    )}
                    
                    <div className={`max-w-[70%] px-5 py-3 rounded-2xl text-base shadow-sm ${
                      msg.sender === 'user' 
                        ? 'bg-teal-600 text-white rounded-tr-none' 
                        : 'bg-white text-gray-800 rounded-tl-none border border-gray-100'
                    }`}>
                      {msg.text}
                    </div>
                </div>
              </div>
            ))}
          </div>
        ) : (
          <div className="flex flex-col items-center justify-center text-gray-400 space-y-4 opacity-40">
            <div className="w-20 h-20 rounded-full bg-white/50 flex items-center justify-center border-2 border-dashed border-gray-300">
               <Glasses size={36} />
            </div>
            <div className="text-center">
                <p className="text-sm font-medium">暂无对话记录</p>
                <p className="text-xs mt-1">连接眼镜后，您可以开始语音交互</p>
            </div>
          </div>
        )}
        <div className="mt-auto pt-8 text-center text-[10px] text-gray-300 uppercase tracking-widest font-bold">
            Powered by Gemini AI
        </div>
      </div>
    </div>
  );
};

export default AssistantTab;