import React from 'react';

/* Decorative hand-drawn marks (the Intelly visual language) */
export const Doodles = {
  Heart: ({size=22, color="#0e0e0e", style}) => (
    <svg width={size} height={size} viewBox="0 0 32 32" fill="none" style={style}>
      <path d="M16 27S5 19 5 12.5C5 8 8 6 11 6c2 0 4 1 5 3 1-2 3-3 5-3 3 0 6 2 6 6.5C27 19 16 27 16 27z" stroke={color} strokeWidth="1.8" strokeLinejoin="round" fill="none"/>
    </svg>
  ),
  Star: ({size=22, color="#0e0e0e", style}) => (
    <svg width={size} height={size} viewBox="0 0 32 32" fill="none" style={style}>
      <path d="M16 4c1 6 6 11 12 12-6 1-11 6-12 12-1-6-6-11-12-12 6-1 11-6 12-12z" stroke={color} strokeWidth="1.8" strokeLinejoin="round" fill="none"/>
    </svg>
  ),
  Asterisk: ({size=20, color="#0e0e0e", style}) => (
    <svg width={size} height={size} viewBox="0 0 32 32" fill="none" style={style}>
      <path d="M16 4v24M6 10l20 12M6 22l20-12" stroke={color} strokeWidth="2" strokeLinecap="round"/>
    </svg>
  ),
  Squiggle: ({w=80, color="#0e0e0e", style}) => (
    <svg width={w} height="20" viewBox="0 0 80 20" fill="none" style={style}>
      <path d="M2 10c5-8 10 8 15 0s10-8 15 0 10 8 15 0 10-8 15 0 10 8 15 0" stroke={color} strokeWidth="1.6" strokeLinecap="round" fill="none"/>
    </svg>
  ),
  Sparkle: ({size=24, color="#0e0e0e", style}) => (
    <svg width={size} height={size} viewBox="0 0 32 32" fill="none" style={style}>
      <path d="M16 4l2.4 9.6L28 16l-9.6 2.4L16 28l-2.4-9.6L4 16l9.6-2.4z" stroke={color} strokeWidth="1.8" strokeLinejoin="round" fill="none"/>
    </svg>
  ),
  Brain: ({size=26, color="#0e0e0e", style}) => (
    <svg width={size} height={size} viewBox="0 0 32 32" fill="none" style={style}>
      <path d="M10 8c-3 0-4 3-3 5-2 1-2 5 0 6 0 3 3 5 6 4 1 2 5 2 6 0 3 1 6-1 6-4 2-1 2-5 0-6 1-2 0-5-3-5-1-2-5-2-6 0-1-2-5-2-6 0z" stroke={color} strokeWidth="1.6" strokeLinejoin="round" fill="none"/>
      <path d="M16 8v18M11 13c2 1 4 1 5 0M11 19c2 1 4 1 5 0M16 13c1-1 3-1 5 0M16 19c1-1 3-1 5 0" stroke={color} strokeWidth="1.2" strokeLinecap="round"/>
    </svg>
  ),
  Lightning: ({size=22, color="#0e0e0e", style}) => (
    <svg width={size} height={size} viewBox="0 0 32 32" fill="none" style={style}>
      <path d="M17 3L7 18h7l-2 11 10-15h-7z" stroke={color} strokeWidth="1.8" strokeLinejoin="round" fill="none"/>
    </svg>
  ),
  Burst: ({size=42, color="#0e0e0e", style}) => (
    <svg width={size} height={size} viewBox="0 0 48 48" fill="none" style={style}>
      <path d="M24 4v8M24 36v8M4 24h8M36 24h8M10 10l5 5M33 33l5 5M38 10l-5 5M15 33l-5 5" stroke={color} strokeWidth="1.6" strokeLinecap="round"/>
    </svg>
  ),
  Wave: ({w=80, color="#0e0e0e", style}) => (
    <svg width={w} height="14" viewBox="0 0 80 14" fill="none" style={style}>
      <path d="M2 7q5-8 10 0t10 0 10 0 10 0 10 0 10 0 10 0 10 0" stroke={color} strokeWidth="1.6" strokeLinecap="round" fill="none"/>
    </svg>
  ),
};
