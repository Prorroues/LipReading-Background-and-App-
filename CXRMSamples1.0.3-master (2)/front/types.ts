import { LucideIcon } from 'lucide-react';

export enum Tab {
  ASSISTANT = 'assistant',
  HOME = 'home',
  GALLERY = 'gallery',
  MINE = 'mine'
}

export interface ChatMessage {
  id: string;
  sender: 'user' | 'system';
  text: string;
  timestamp: string;
}

export interface FeatureCardProps {
  icon: LucideIcon;
  title: string;
  subtitle: string;
  color: string;
  isBeta?: boolean;
}

export interface GalleryItem {
  id: string;
  type: 'image' | 'video';
  url: string;
  duration?: string;
}

export interface GallerySection {
  date: string;
  items: GalleryItem[];
}
