import React, { useState } from 'react';
import { Video, Scan, CheckSquare, Settings, Image as ImageIcon } from 'lucide-react';
import { GallerySection } from '../types';

const GalleryTab: React.FC = () => {
  const [filter, setFilter] = useState<'video' | 'image'>('video');

  // Empty data for initial state
  const galleryData: GallerySection[] = [];

  // Filter data based on selection
  const filteredData = galleryData.map(section => ({
    ...section,
    items: section.items.filter(item => item.type === filter)
  })).filter(section => section.items.length > 0);

  return (
    <div className="flex flex-col h-full bg-gradient-to-b from-teal-50 to-gray-50 overflow-y-auto pb-24">
       {/* Header */}
       <header className="flex justify-between items-center p-4 pt-8 bg-transparent backdrop-blur-sm sticky top-0 z-20">
        <h1 className="text-3xl font-bold text-gray-800">相册</h1>
        
        <div className="flex items-center space-x-1 bg-gray-100/80 rounded-full px-1 py-1">
             <button 
                onClick={() => setFilter('video')}
                className={`flex items-center space-x-1 px-3 py-1.5 rounded-full transition-all text-xs font-semibold ${
                    filter === 'video' 
                    ? 'bg-white shadow-sm text-gray-800' 
                    : 'text-gray-500 hover:text-gray-700'
                }`}
             >
                <Video size={14} />
                <span>录屏</span>
             </button>
             <button 
                onClick={() => setFilter('image')}
                className={`flex items-center space-x-1 px-3 py-1.5 rounded-full transition-all text-xs font-semibold ${
                    filter === 'image' 
                    ? 'bg-white shadow-sm text-gray-800' 
                    : 'text-gray-500 hover:text-gray-700'
                }`}
             >
                <Scan size={14} />
                <span>截图</span>
             </button>
        </div>

        <div className="flex space-x-4 text-gray-700">
          <CheckSquare size={24} strokeWidth={1.5} />
          <Settings size={24} strokeWidth={1.5} />
        </div>
      </header>

      <div className="p-1 space-y-6 flex-1 flex flex-col">
        {filteredData.length > 0 ? (
            filteredData.map((section) => (
                <div key={section.date}>
                    <h3 className="px-3 py-2 text-lg font-semibold text-gray-800">{section.date}</h3>
                    <div className="grid grid-cols-4 gap-0.5">
                        {section.items.map((item) => (
                            <div key={item.id} className="relative aspect-[3/4] bg-gray-200 overflow-hidden cursor-pointer group">
                                <img 
                                    src={item.url} 
                                    alt="" 
                                    className="w-full h-full object-cover transition-transform duration-300 group-hover:scale-105"
                                    loading="lazy"
                                />
                                {item.type === 'video' && (
                                    <div className="absolute bottom-1 right-1 bg-black/50 text-white text-[10px] px-1 rounded backdrop-blur-sm">
                                        {item.duration}
                                    </div>
                                )}
                            </div>
                        ))}
                    </div>
                </div>
            ))
        ) : (
            <div className="flex-1 flex flex-col items-center justify-center text-gray-400 opacity-60">
                <div className="w-20 h-20 rounded-full bg-gray-100 flex items-center justify-center mb-4">
                  <ImageIcon size={40} />
                </div>
                <span className="text-base font-medium">暂无内容</span>
                <p className="text-xs mt-1">眼镜拍摄的照片和视频将显示在这里</p>
            </div>
        )}
      </div>
    </div>
  );
};

export default GalleryTab;