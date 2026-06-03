import React, { useState } from 'react';
import { ChevronRight, ChevronLeft, User, Info, HelpCircle, Sparkles, Cpu, Key, Eye, EyeOff, Save, Mic } from 'lucide-react';

const MineTab: React.FC = () => {
  const [currentView, setCurrentView] = useState<'main' | 'ai_settings' | 'speech_settings'>('main');

  // State for Large Model API Keys
  const [apiKeys, setApiKeys] = useState([
    { id: 'openai', name: 'OpenAI', key: '', placeholder: 'sk-...' },
    { id: 'google', name: 'Google Gemini', key: '', placeholder: 'AIza...' },
    { id: 'anthropic', name: 'Anthropic Claude', key: '', placeholder: 'sk-ant-...' },
    { id: 'deepseek', name: 'DeepSeek', key: '', placeholder: 'sk-...' },
    { id: 'dashscope', name: '阿里通义 (DashScope)', key: '', placeholder: 'sk-...' },
    { id: 'qianfan', name: '百度文心 (Qianfan)', key: '', placeholder: 'Access Key...' },
  ]);

  // State for Speech Recognition API Keys (AliCloud)
  const [aliSpeechKeys, setAliSpeechKeys] = useState([
    { id: 'appkey', name: 'AppKey', key: '', placeholder: '项目 AppKey' },
    { id: 'access_key_id', name: 'AccessKey ID', key: '', placeholder: 'LTAI...' },
    { id: 'access_key_secret', name: 'AccessKey Secret', key: '', placeholder: 'Secret...' },
  ]);

  const [visibleKeys, setVisibleKeys] = useState<Record<string, boolean>>({});

  const handleKeyChange = (id: string, val: string) => {
    setApiKeys(prev => prev.map(item => item.id === id ? { ...item, key: val } : item));
  };

  const handleAliKeyChange = (id: string, val: string) => {
    setAliSpeechKeys(prev => prev.map(item => item.id === id ? { ...item, key: val } : item));
  };

  const toggleVisibility = (id: string) => {
    setVisibleKeys(prev => ({ ...prev, [id]: !prev[id] }));
  };

  const handleSave = () => {
    setCurrentView('main');
  };

  // Sub-view: Large Model API Settings
  if (currentView === 'ai_settings') {
    return (
      <div className="flex flex-col h-full bg-gray-50 overflow-y-auto pb-24">
         <header className="flex items-center justify-between p-4 bg-white sticky top-0 z-10 shadow-sm">
            <button 
                onClick={() => setCurrentView('main')}
                className="p-2 -ml-2 text-gray-600 hover:bg-gray-100 rounded-full transition-colors"
            >
                <ChevronLeft size={24} />
            </button>
            <h1 className="text-lg font-bold text-gray-800">大模型 API 设置</h1>
            <div className="w-10"></div>
         </header>

         <div className="p-4 space-y-4">
            <div className="bg-teal-50 text-teal-700 p-4 rounded-xl text-sm flex gap-3 border border-teal-100">
                <Info size={20} className="shrink-0" />
                <p>请在此处配置各大模型厂商的 API Key。配置后，助手将能够调用相应的模型能力为您服务。</p>
            </div>

            <div className="space-y-3">
                {apiKeys.map((item) => (
                    <div key={item.id} className="bg-white p-4 rounded-xl border border-gray-100 shadow-sm">
                        <div className="flex items-center mb-2">
                             <span className="font-semibold text-gray-700">{item.name}</span>
                        </div>
                        <div className="relative">
                            <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none text-gray-400">
                                <Key size={16} />
                            </div>
                            <input 
                                type={visibleKeys[item.id] ? "text" : "password"}
                                value={item.key}
                                onChange={(e) => handleKeyChange(item.id, e.target.value)}
                                placeholder={item.placeholder}
                                className="w-full pl-10 pr-10 py-3 bg-gray-50 border-gray-200 rounded-lg text-sm focus:ring-2 focus:ring-teal-500 focus:border-transparent outline-none transition-all"
                            />
                            <button 
                                onClick={() => toggleVisibility(item.id)}
                                className="absolute inset-y-0 right-0 pr-3 flex items-center text-gray-400 hover:text-gray-600"
                            >
                                {visibleKeys[item.id] ? <EyeOff size={18} /> : <Eye size={18} />}
                            </button>
                        </div>
                    </div>
                ))}
            </div>

            <button 
                onClick={handleSave}
                className="w-full bg-teal-600 hover:bg-teal-700 text-white font-semibold py-3.5 rounded-xl shadow-lg shadow-teal-600/20 active:scale-[0.98] transition-all flex items-center justify-center gap-2 mt-6"
            >
                <Save size={20} />
                <span>保存设置</span>
            </button>
         </div>
      </div>
    );
  }

  // Sub-view: Speech Recognition API Settings
  if (currentView === 'speech_settings') {
    return (
      <div className="flex flex-col h-full bg-gray-50 overflow-y-auto pb-24">
         <header className="flex items-center justify-between p-4 bg-white sticky top-0 z-10 shadow-sm">
            <button 
                onClick={() => setCurrentView('main')}
                className="p-2 -ml-2 text-gray-600 hover:bg-gray-100 rounded-full transition-colors"
            >
                <ChevronLeft size={24} />
            </button>
            <h1 className="text-lg font-bold text-gray-800">语音识别 API 设置</h1>
            <div className="w-10"></div>
         </header>

         <div className="p-4 space-y-4">
            <div className="bg-blue-50 text-blue-700 p-4 rounded-xl text-sm flex gap-3 border border-blue-100">
                <Mic size={20} className="shrink-0" />
                <p>请在此处配置阿里云语音识别 (ASR/TTS) 的密钥信息，用于实现眼镜端的语音交互功能。</p>
            </div>

            <div className="space-y-3">
                {aliSpeechKeys.map((item) => (
                    <div key={item.id} className="bg-white p-4 rounded-xl border border-gray-100 shadow-sm">
                        <div className="flex items-center mb-2">
                             <span className="font-semibold text-gray-700">{item.name}</span>
                        </div>
                        <div className="relative">
                            <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none text-gray-400">
                                <Key size={16} />
                            </div>
                            <input 
                                type={visibleKeys[item.id] ? "text" : "password"}
                                value={item.key}
                                onChange={(e) => handleAliKeyChange(item.id, e.target.value)}
                                placeholder={item.placeholder}
                                className="w-full pl-10 pr-10 py-3 bg-gray-50 border-gray-200 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition-all"
                            />
                            <button 
                                onClick={() => toggleVisibility(item.id)}
                                className="absolute inset-y-0 right-0 pr-3 flex items-center text-gray-400 hover:text-gray-600"
                            >
                                {visibleKeys[item.id] ? <EyeOff size={18} /> : <Eye size={18} />}
                            </button>
                        </div>
                    </div>
                ))}
            </div>

            <button 
                onClick={handleSave}
                className="w-full bg-blue-600 hover:bg-blue-700 text-white font-semibold py-3.5 rounded-xl shadow-lg shadow-blue-600/20 active:scale-[0.98] transition-all flex items-center justify-center gap-2 mt-6"
            >
                <Save size={20} />
                <span>保存设置</span>
            </button>
         </div>
      </div>
    );
  }

  // Main View
  return (
    <div className="flex flex-col h-full bg-gradient-to-b from-teal-50 to-gray-50 overflow-y-auto pb-24">
      {/* Header Profile */}
      <div className="p-6 pt-12 flex items-center justify-between mb-4 pb-8">
         <div className="flex items-center space-x-4">
            <div className="w-16 h-16 rounded-full bg-pink-100 overflow-hidden border-2 border-white shadow-sm">
                <img src="https://picsum.photos/200/200?random=user" alt="Avatar" className="w-full h-full object-cover" />
            </div>
            <div>
                <h2 className="text-2xl font-bold text-gray-900">信风</h2>
            </div>
         </div>
         <div className="flex items-center text-gray-500 text-sm">
            <span>个人空间</span>
            <ChevronRight size={16} />
         </div>
      </div>

      {/* Settings Menu */}
      <div className="bg-white mx-4 rounded-2xl shadow-sm overflow-hidden mb-6">
          <div className="flex items-center justify-between p-4 active:bg-gray-50 cursor-pointer border-b border-gray-50 transition-colors hover:bg-gray-50">
              <div className="flex items-center space-x-3">
                  <User size={20} className="text-gray-700" />
                  <span className="text-gray-800 text-sm font-medium">账号管理</span>
              </div>
              <ChevronRight size={16} className="text-gray-400" />
          </div>

          <div 
            onClick={() => setCurrentView('ai_settings')}
            className="flex items-center justify-between p-4 active:bg-gray-50 cursor-pointer border-b border-gray-50 transition-colors hover:bg-gray-50"
          >
              <div className="flex items-center space-x-3">
                  <Sparkles size={20} className="text-gray-700" />
                  <span className="text-gray-800 text-sm font-medium">大模型 API 设置</span>
              </div>
              <ChevronRight size={16} className="text-gray-400" />
          </div>

          <div 
            onClick={() => setCurrentView('speech_settings')}
            className="flex items-center justify-between p-4 active:bg-gray-50 cursor-pointer border-b border-gray-50 transition-colors hover:bg-gray-50"
          >
              <div className="flex items-center space-x-3">
                  <Mic size={20} className="text-gray-700" />
                  <span className="text-gray-800 text-sm font-medium">语音识别 API 设置</span>
              </div>
              <ChevronRight size={16} className="text-gray-400" />
          </div>

          <div className="flex items-center justify-between p-4 active:bg-gray-50 cursor-pointer border-b border-gray-50 transition-colors hover:bg-gray-50">
              <div className="flex items-center space-x-3">
                  <Cpu size={20} className="text-gray-700" />
                  <span className="text-gray-800 text-sm font-medium">设备信息</span>
              </div>
              <ChevronRight size={16} className="text-gray-400" />
          </div>

          <div className="flex items-center justify-between p-4 active:bg-gray-50 cursor-pointer border-b border-gray-50 transition-colors hover:bg-gray-50">
              <div className="flex items-center space-x-3">
                  <Info size={20} className="text-gray-700" />
                  <span className="text-gray-800 text-sm font-medium">应用信息</span>
              </div>
              <ChevronRight size={16} className="text-gray-400" />
          </div>
           <div className="flex items-center justify-between p-4 active:bg-gray-50 cursor-pointer transition-colors hover:bg-gray-50">
              <div className="flex items-center space-x-3">
                  <HelpCircle size={20} className="text-gray-700" />
                  <span className="text-gray-800 text-sm font-medium">问题与反馈</span>
              </div>
              <ChevronRight size={16} className="text-gray-400" />
          </div>
      </div>

    </div>
  );
};

export default MineTab;