'use client';

import React, {
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import RubberSegment from '@/bits/RubberSegment/RubberSegment';

/* ------------------------------------------------------------------ */
/* FloatingNav —— 居中悬浮胶囊导航，向下滚动时收起、向上滚动时展开      */
/* 段位高亮用 RubberSegment（无轨道底），scroll-spy 驱动滑块            */
/* ------------------------------------------------------------------ */

export interface FloatingNavItem {
  name: string;
  link?: string;
  /** 用 onClick 走平滑滚动，避免 hash 路由把 #section 当成路由 */
  onClick?: () => void;
}

interface FloatingNavProps {
  navItems: FloatingNavItem[];
  /** 胶囊左侧品牌区（Logo / 品牌名） */
  logo?: ReactNode;
  /** 胶囊右侧操作区（登录按钮等） */
  action?: ReactNode;
  /** 当前高亮项的 name（scroll-spy 传入） */
  activeName?: string;
  /** 滚动超过这个距离才开始响应方向（px） */
  offset?: number;
  /** nav 与分段控件的无障碍名（调用方按语言传入） */
  navAria?: string;
  segmentAria?: string;
  className?: string;
}

export function FloatingNav({
  navItems,
  logo,
  action,
  activeName,
  offset = 80,
  navAria,
  segmentAria,
  className = '',
}: FloatingNavProps) {
  const [visible, setVisible] = useState(true);
  const lastY = useRef(0);
  const ticking = useRef(false);
  const reduce = useReducedMotion();
  const current = activeName && navItems.some((i) => i.name === activeName)
    ? activeName
    : navItems[0]?.name;

  useEffect(() => {
    lastY.current = window.scrollY;

    const onScroll = () => {
      if (ticking.current) return;
      ticking.current = true;
      requestAnimationFrame(() => {
        const y = window.scrollY;
        if (y <= offset) {
          // 顶部附近始终显示
          setVisible(true);
        } else if (y > lastY.current + 6) {
          setVisible(false); // 向下滚：收起
        } else if (y < lastY.current - 6) {
          setVisible(true); // 向上滚：展开
        }
        lastY.current = y;
        ticking.current = false;
      });
    };

    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, [offset]);

  return (
    <AnimatePresence>
      {visible && (
        <motion.div
          initial={reduce ? false : { y: -72, opacity: 0 }}
          animate={{ y: 0, opacity: 1 }}
          exit={reduce ? { opacity: 0 } : { y: -72, opacity: 0 }}
          transition={{ duration: 0.32, ease: [0.23, 1, 0.32, 1] }}
          className={`floating-nav-viewport ${className}`}
        >
          <div className="floating-nav">
            {logo && <div className="floating-nav__logo">{logo}</div>}

            <nav className="floating-nav__items" aria-label={navAria}>
              <RubberSegment
                className="floating-nav__segment"
                items={navItems.map((item) => item.name)}
                value={current}
                animateExternal
                equalSlots={false}
                size="sm"
                radius={999}
                inset={2}
                trackColor="transparent"
                thumbColor="var(--primary-container)"
                textColor="var(--text-2)"
                activeTextColor="var(--primary-text)"
                draggable={!reduce}
                aria-label={segmentAria}
                onChange={(name) => {
                  if (name === current) return;
                  const hit = navItems.find((i) => i.name === name);
                  if (!hit) return;
                  if (hit.onClick) hit.onClick();
                  else if (hit.link) window.location.assign(hit.link);
                }}
              />
            </nav>

            {action && <div className="floating-nav__action">{action}</div>}
          </div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}

export default FloatingNav;
