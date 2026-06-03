import React, { useState } from 'react';
import { 
  Plus, Settings, Volume2, Sun, 
  Languages, Type, Navigation, FileCheck, 
  Eye, MessageSquareQuote, ChevronLeft, Globe, Zap, Save,
  Mic, Layout, FileCode, Hand, Play, Square, FileText, Construction, Code, Monitor, Sparkles
} from 'lucide-react';
import { FeatureCardProps } from '../types';

const FeatureCard: React.FC<FeatureCardProps & { onClick?: () => void }> = ({ icon: Icon, title, subtitle, color, isBeta, onClick }) => (
  <div 
    onClick={onClick}
    className="bg-white p-4 rounded-2xl shadow-sm border border-gray-100 flex flex-col justify-between h-28 active:scale-95 transition-transform duration-100 cursor-pointer"
  >
    <div className="flex justify-between items-start">
      <div className="text-gray-800 font-semibold text-lg leading-tight">{title}</div>
       <div className={`p-2 rounded-xl ${color} text-white`}>
        <Icon size={20} fill="currentColor" className="opacity-90"/>
      </div>
    </div>
    <div className="flex justify-between items-end">
        <div className="text-[10px] text-gray-400 line-clamp-1">{subtitle}</div>
        {isBeta && (
            <span className="bg-emerald-100 text-emerald-600 text-[10px] px-1.5 py-0.5 rounded font-bold ml-1 shrink-0">
                AI
            </span>
        )}
    </div>
  </div>
);

type HomeView = 'main' | 'lip_reading' | 'recording' | 'translation' | 'navigation' | 'meeting_minutes' | 'vision' | 'sign_language' | 'teleprompter' | 'custom_view' | 'custom_protocol';

const HomeTab: React.FC = () => {
  const [currentView, setCurrentView] = useState<HomeView>('main');
  const [volume, setVolume] = useState(40);
  const [brightness, setBrightness] = useState(60);

  // States for various modules
  const [isRecording, setIsRecording] = useState(false);
  const [recordingMode, setRecordingMode] = useState<'near' | 'far' | 'global'>('near');
  const [isTranslating, setIsTranslating] = useState(false);
  const [translationText, setTranslationText] = useState('');
  const [meetingText, setMeetingText] = useState('');
  const [isVisionActive, setIsVisionActive] = useState(false);
  const [isSignActive, setIsSignActive] = useState(false);

  const renderHeader = (title: string) => (
    <header className="flex items-center justify-between p-4 bg-white sticky top-0 z-10 shadow-sm">
      <button 
        onClick={() => setCurrentView('main')}
        className="p-2 -ml-2 text-gray-600 hover:bg-gray-100 rounded-full transition-colors"
      >
        <ChevronLeft size={24} />
      </button>
      <h1 className="text-lg font-bold text-gray-800">{title}</h1>
      <div className="w-10"></div>
    </header>
  );

  const SubViewContainer = ({ title, children }: { title: string, children: React.ReactNode }) => (
    <div className="flex flex-col h-full bg-gray-50 overflow-y-auto pb-24">
      {renderHeader(title)}
      <div className="p-4 space-y-6">
        {children}
      </div>
    </div>
  );

  const DevelopmentPlaceholder = ({ title, icon: Icon }: { title: string, icon: any }) => (
    <SubViewContainer title={title}>
      <div className="flex flex-col items-center justify-center py-20 text-gray-400">
        <div className="bg-gray-100 p-6 rounded-full mb-4">
          <Icon size={48} className="text-gray-400" />
        </div>
        <h2 className="text-xl font-bold text-gray-800">功能待开发</h2>
        <p className="text-sm mt-2">{title}能力即将上线，敬请期待</p>
      </div>
    </SubViewContainer>
  );

  if (currentView === 'lip_reading') {
    return (
      <SubViewContainer title="唇语识别设置">
        <div className="bg-blue-50 text-blue-700 p-4 rounded-xl text-sm flex gap-3 border border-blue-100">
          <Zap size={20} className="shrink-0" />
          <p>配置唇语识别的语义分发逻辑与数据上报地址。</p>
        </div>
        <div className="space-y-4">
          <div className="bg-white p-5 rounded-2xl shadow-sm border border-gray-100">
            <label className="block text-sm font-semibold text-gray-700 mb-2">提示词语义路由</label>
            <textarea placeholder="例如：识别到'拍照'..." className="w-full p-4 bg-gray-50 border border-gray-200 rounded-xl text-sm min-h-[120px] outline-none resize-none" />
          </div>
        </div>
        <button onClick={() => setCurrentView('main')} className="w-full bg-blue-600 text-white font-semibold py-4 rounded-2xl">保存配置</button>
      </SubViewContainer>
    );
  }

  if (currentView === 'recording') {
    return (
      <SubViewContainer title="录音中心">
        <div className="bg-white p-6 rounded-3xl shadow-sm border border-gray-100 flex flex-col items-center">
          <div className={`w-24 h-24 rounded-full flex items-center justify-center mb-6 transition-all ${isRecording ? 'bg-red-50 animate-pulse' : 'bg-gray-50'}`}>
            <Mic size={40} className={isRecording ? 'text-red-500' : 'text-gray-400'} />
          </div>
          <div className="flex bg-gray-100 p-1 rounded-xl mb-8 w-full">
            {(['near', 'far', 'global'] as const).map(mode => (
              <button
                key={mode}
                onClick={() => setRecordingMode(mode)}
                className={`flex-1 py-2 text-xs font-bold rounded-lg transition-all ${recordingMode === mode ? 'bg-white text-gray-800 shadow-sm' : 'text-gray-400'}`}
              >
                {mode === 'near' ? '近场' : mode === 'far' ? '远场' : '全局'}
              </button>
            ))}
          </div>
          <div className="flex gap-4 w-full">
            <button 
              onClick={() => setIsRecording(true)}
              disabled={isRecording}
              className={`flex-1 flex items-center justify-center gap-2 py-4 rounded-2xl font-bold transition-all ${isRecording ? 'bg-gray-100 text-gray-400' : 'bg-red-500 text-white shadow-lg shadow-red-500/20'}`}
            >
              <Play size={18} fill="currentColor" /> 开始录音
            </button>
            <button 
              onClick={() => setIsRecording(false)}
              disabled={!isRecording}
              className={`flex-1 flex items-center justify-center gap-2 py-4 rounded-2xl font-bold border-2 transition-all ${!isRecording ? 'border-gray-200 text-gray-300' : 'border-red-500 text-red-500'}`}
            >
              <Square size={18} fill="currentColor" /> 停止录音
            </button>
          </div>
        </div>
      </SubViewContainer>
    );
  }

  if (currentView === 'translation') {
    return (
      <SubViewContainer title="实时翻译">
        <div className="bg-white p-5 rounded-2xl shadow-sm border border-gray-100 min-h-[300px] flex flex-col">
          <div className="flex-1 text-gray-600 text-sm leading-relaxed p-2 bg-gray-50 rounded-xl overflow-y-auto">
            {translationText || (isTranslating ? "正在收音并翻译中...\n\nHello, welcome to the AR world. (你好，欢迎来到增强现实世界。)" : "翻译内容将在此处显示...")}
          </div>
          <button 
            onClick={() => setIsTranslating(!isTranslating)}
            className={`w-full mt-6 py-4 rounded-xl font-bold flex items-center justify-center gap-2 transition-all ${isTranslating ? 'bg-amber-100 text-amber-600' : 'bg-emerald-600 text-white shadow-lg shadow-emerald-600/20'}`}
          >
            {isTranslating ? <Square size={18} fill="currentColor" /> : <Play size={18} fill="currentColor" />}
            {isTranslating ? '停止翻译' : '开始实时翻译'}
          </button>
        </div>
      </SubViewContainer>
    );
  }

  if (currentView === 'navigation') return <DevelopmentPlaceholder title="智能导航" icon={Construction} />;
  if (currentView === 'teleprompter') return <DevelopmentPlaceholder title="提词器" icon={Type} />;
  if (currentView === 'custom_view') return <DevelopmentPlaceholder title="自定义View" icon={Layout} />;
  if (currentView === 'custom_protocol') return <DevelopmentPlaceholder title="自定义协议" icon={FileCode} />;

  if (currentView === 'meeting_minutes') {
    return (
      <SubViewContainer title="会议纪要">
        <div className="space-y-4">
          <div className="bg-white p-5 rounded-2xl shadow-sm border border-gray-100 h-80 overflow-y-auto">
             <div className="text-xs text-gray-400 mb-2 font-mono uppercase">Original Transcript</div>
            <p className="text-sm text-gray-500 leading-relaxed italic border-b border-gray-50 pb-4 mb-4">
               {isRecording ? "正在实时转录会议发言..." : "录音转写的原始文本将在此处累积。"}
            </p>
             <div className="text-xs text-teal-600 mb-2 font-bold uppercase">AI Summary</div>
             <p className="text-sm text-gray-800 leading-relaxed font-medium">
               {meetingText || "点击下方按钮开始 AI 总结。"}
            </p>
          </div>
          <button 
            onClick={() => setMeetingText("会议摘要：\n1. 讨论了 AR 眼镜 2.5 的核心性能指标。\n2. 确定了下一代手势交互方案。\n3. 会议决定下周五进行内部封闭测试。")}
            className="w-full bg-purple-600 text-white font-bold py-4 rounded-xl flex items-center justify-center gap-2 shadow-lg shadow-purple-600/20 active:scale-95 transition-transform"
          >
            <Sparkles size={18} className="text-white" /> 开始总结
          </button>
        </div>
      </SubViewContainer>
    );
  }

  if (currentView === 'vision') {
    return (
      <SubViewContainer title="慧眼感知">
        <div className="bg-white p-8 rounded-3xl shadow-sm border border-gray-100 flex flex-col items-center">
          <div className={`w-32 h-32 rounded-full flex items-center justify-center mb-8 border-4 transition-all ${isVisionActive ? 'border-emerald-500 bg-emerald-50 shadow-[0_0_20px_rgba(16,185,129,0.2)]' : 'border-gray-100 bg-gray-50'}`}>
            <Eye size={50} className={isVisionActive ? 'text-emerald-500' : 'text-gray-300'} />
          </div>
          <button 
            onClick={() => setIsVisionActive(!isVisionActive)}
            className={`w-full py-5 rounded-2xl font-bold text-lg shadow-lg transition-all ${isVisionActive ? 'bg-gray-100 text-gray-500' : 'bg-emerald-500 text-white shadow-emerald-500/20'}`}
          >
            {isVisionActive ? '关闭慧眼' : '开启慧眼'}
          </button>
          <div className="mt-8 grid grid-cols-2 gap-3 w-full">
            <div className="bg-gray-50 p-3 rounded-xl border border-gray-100">
                <div className="text-[10px] text-gray-400 uppercase font-bold">识别频率</div>
                <div className="text-sm font-semibold text-gray-700">1.5s / 帧</div>
            </div>
            <div className="bg-gray-50 p-3 rounded-xl border border-gray-100">
                <div className="text-[10px] text-gray-400 uppercase font-bold">当前模式</div>
                <div className="text-sm font-semibold text-gray-700">全能助手</div>
            </div>
          </div>
        </div>
      </SubViewContainer>
    );
  }

  if (currentView === 'sign_language') {
    return (
      <SubViewContainer title="手语识别">
        <div className="bg-white p-8 rounded-3xl shadow-sm border border-gray-100 flex flex-col items-center">
          <div className={`w-32 h-32 rounded-full flex items-center justify-center mb-8 border-4 transition-all ${isSignActive ? 'border-blue-500 bg-blue-50 animate-pulse' : 'border-gray-100 bg-gray-50'}`}>
            <Hand size={50} className={isSignActive ? 'text-blue-500' : 'text-gray-300'} />
          </div>
          <button 
            onClick={() => setIsSignActive(!isSignActive)}
            className={`w-full py-5 rounded-2xl font-bold text-lg shadow-lg transition-all ${isSignActive ? 'bg-gray-100 text-gray-500' : 'bg-blue-600 text-white shadow-blue-600/20'}`}
          >
            {isSignActive ? '停止识别' : '开启手语识别'}
          </button>
          <div className="mt-6 w-full p-6 bg-gray-50 rounded-2xl min-h-[140px] border-2 border-dashed border-gray-200 text-center flex flex-col items-center justify-center">
            {isSignActive ? (
                <div className="space-y-2">
                    <span className="text-blue-600 font-bold text-xl animate-bounce inline-block">[ 你好 ]</span>
                    <p className="text-gray-500 text-sm italic">正在实时翻译手势行为...</p>
                </div>
            ) : (
                <div className="text-gray-400 text-sm">
                   <Monitor size={24} className="mx-auto mb-2 opacity-30" />
                   翻译后的文本将在此显示
                </div>
            )}
          </div>
        </div>
      </SubViewContainer>
    );
  }

  return (
    <div className="flex flex-col h-full bg-gradient-to-b from-teal-50/50 to-gray-50 overflow-y-auto pb-24">
      {/* Header */}
      <header className="flex justify-between items-center p-4 pt-8 sticky top-0 bg-teal-50/90 backdrop-blur-sm z-10">
        <h1 className="text-3xl font-bold text-gray-800">主页</h1>
        <div className="flex space-x-4 text-gray-700">
          <Plus size={26} strokeWidth={1.5} />
          <Settings size={24} strokeWidth={1.5} />
        </div>
      </header>

      {/* Device Hero */}
      <div className="flex flex-col items-center py-6">
        <div className="relative w-64 h-32 mb-4">
             <img 
                src="https://images.unsplash.com/photo-1572635196237-14b3f281503f?q=80&w=1000&auto=format&fit=crop" 
                alt="AR Glasses" 
                className="w-full h-full object-contain mix-blend-multiply opacity-90"
             />
        </div>
        <h2 className="text-xl font-semibold text-gray-600">Glasses_0919</h2>
        <div className="flex items-center mt-2 text-green-600 font-medium text-xs">
          <span className="w-2 h-2 bg-green-500 rounded-full mr-1.5 animate-pulse"></span>
          <span>已连接</span>
        </div>
      </div>

      {/* Quick Controls */}
      <div className="grid grid-cols-2 gap-4 px-4 mb-6">
        <div className="bg-white p-4 rounded-2xl shadow-sm flex flex-col justify-center space-y-3 relative overflow-hidden">
          <div className="flex justify-between items-center text-gray-500">
            <span className="text-sm font-medium">音量</span>
            <Volume2 size={16} />
          </div>
          <div className="relative w-full h-6 flex items-center">
             <input type="range" min="0" max="100" value={volume} onChange={(e) => setVolume(Number(e.target.value))} className="absolute w-full h-full opacity-0 z-10 cursor-pointer" />
             <div className="w-full h-2 bg-gray-100 rounded-full overflow-hidden relative pointer-events-none">
                <div className="absolute top-0 left-0 h-full bg-emerald-500 rounded-full transition-all duration-75" style={{ width: `${volume}%` }}></div>
             </div>
          </div>
        </div>
         <div className="bg-white p-4 rounded-2xl shadow-sm flex flex-col justify-center space-y-3 relative overflow-hidden">
          <div className="flex justify-between items-center text-gray-500">
            <span className="text-sm font-medium">亮度</span>
            <Sun size={16} />
          </div>
          <div className="relative w-full h-6 flex items-center">
              <input type="range" min="0" max="100" value={brightness} onChange={(e) => setBrightness(Number(e.target.value))} className="absolute w-full h-full opacity-0 z-10 cursor-pointer" />
             <div className="w-full h-2 bg-gray-100 rounded-full overflow-hidden relative pointer-events-none">
                <div className="absolute top-0 left-0 h-full bg-amber-400 rounded-full transition-all duration-75" style={{ width: `${brightness}%` }}></div>
             </div>
          </div>
        </div>
      </div>

      {/* Feature Grid */}
      <div className="grid grid-cols-2 gap-4 px-4">
        <FeatureCard 
            icon={MessageSquareQuote} title="唇语识别" subtitle="无声交互 意图识别" color="bg-blue-600" isBeta
            onClick={() => setCurrentView('lip_reading')}
        />
        <FeatureCard 
            icon={Mic} title="录音" subtitle="三种模式 高清录制" color="bg-red-400" 
            onClick={() => setCurrentView('recording')}
        />
        <FeatureCard 
            icon={Languages} title="翻译" subtitle="实时语音翻译" color="bg-emerald-400" isBeta
            onClick={() => setCurrentView('translation')}
        />
        <FeatureCard 
            icon={Type} title="提词器" subtitle="导入提词内容到眼镜" color="bg-indigo-400" 
            onClick={() => setCurrentView('teleprompter')}
        />
        <FeatureCard 
            icon={Navigation} title="导航" subtitle="AR 智能路线指引" color="bg-blue-500" 
            onClick={() => setCurrentView('navigation')}
        />
        <FeatureCard 
            icon={FileCheck} title="会议纪要" subtitle="转录及智能总结" color="bg-purple-400" 
            onClick={() => setCurrentView('meeting_minutes')}
        />
        <FeatureCard 
            icon={Layout} title="自定义View" subtitle="自由布局 眼镜呈现" color="bg-pink-400" 
            onClick={() => setCurrentView('custom_view')}
        />
         <FeatureCard 
            icon={Eye} title="慧眼 (Beta)" subtitle="环境感知问答" color="bg-emerald-300" isBeta
            onClick={() => setCurrentView('vision')}
        />
         <FeatureCard 
            icon={FileCode} title="自定义协议" subtitle="扩展功能 深度定制" color="bg-slate-500" 
            onClick={() => setCurrentView('custom_protocol')}
        />
        <FeatureCard 
            icon={Hand} title="手语识别" subtitle="视觉理解 辅助沟通" color="bg-blue-400" isBeta
            onClick={() => setCurrentView('sign_language')}
        />
      </div>
    </div>
  );
};

export default HomeTab;