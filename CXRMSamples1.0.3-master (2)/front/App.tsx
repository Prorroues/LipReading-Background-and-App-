import React, { useState } from 'react';
import BottomNav from './components/BottomNav';
import AssistantTab from './components/AssistantTab';
import HomeTab from './components/HomeTab';
import GalleryTab from './components/GalleryTab';
import MineTab from './components/MineTab';
import { Tab } from './types';

const App: React.FC = () => {
  const [activeTab, setActiveTab] = useState<Tab>(Tab.HOME);

  const renderContent = () => {
    switch (activeTab) {
      case Tab.ASSISTANT:
        return <AssistantTab />;
      case Tab.HOME:
        return <HomeTab />;
      case Tab.GALLERY:
        return <GalleryTab />;
      case Tab.MINE:
        return <MineTab />;
      default:
        return <HomeTab />;
    }
  };

  return (
    <div className="relative w-full h-screen bg-gray-100 overflow-hidden mx-auto max-w-md shadow-2xl">
      {/* Main Content Area */}
      <main className="h-full w-full">
        {renderContent()}
      </main>

      {/* Bottom Navigation */}
      <BottomNav activeTab={activeTab} onTabChange={setActiveTab} />
    </div>
  );
};

export default App;