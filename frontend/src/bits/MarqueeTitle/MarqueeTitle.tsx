'use client';

import React, {
  useCallback,
  useEffect,
  useRef,
  useState,
  type CSSProperties,
} from 'react';

/* ------------------------------------------------------------------ */
/* MarqueeTitle —— 单行标题，溢出时悬停后延迟匀速滚动                  */
/* · ResizeObserver 实测标题是否超出容器，短标题不滚动                  */
/* · 悬停/聚焦延迟 500ms 后开始，滚到末尾暂停 800ms 再平滑回起点       */
/* · 移出/失焦立即停止并复位；定时器在卸载时清理                        */
/* · prefers-reduced-motion 时禁用滚动动画                              */
/* ------------------------------------------------------------------ */

interface MarqueeTitleProps {
  text: string;
  className?: string;
  /** 延迟多久后开始滚动（ms），默认 500 */
  delay?: number;
  /** 滚动速度（px/s），默认 36 */
  speed?: number;
}

export function MarqueeTitle({
  text,
  className,
  delay = 500,
  speed = 36,
}: MarqueeTitleProps) {
  const wrapRef = useRef<HTMLSpanElement>(null);
  const innerRef = useRef<HTMLSpanElement>(null);
  const timerRef = useRef<number | undefined>(undefined);
  const [overflowing, setOverflowing] = useState(false);
  const [scrolling, setScrolling] = useState(false);
  const [distance, setDistance] = useState(0);

  /* 实测是否溢出：内层实际宽度 > 外层可视宽度 */
  useEffect(() => {
    const wrap = wrapRef.current;
    const inner = innerRef.current;
    if (!wrap || !inner) return;

    const measure = () => {
      const d = inner.scrollWidth - wrap.clientWidth;
      if (d > 2) {
        setOverflowing(true);
        setDistance(d);
      } else {
        setOverflowing(false);
        setDistance(0);
      }
    };

    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(wrap);
    ro.observe(inner);
    return () => ro.disconnect();
  }, [text]);

  const start = useCallback(() => {
    if (!overflowing) return;
    if (timerRef.current) window.clearTimeout(timerRef.current);
    timerRef.current = window.setTimeout(() => setScrolling(true), delay);
  }, [overflowing, delay]);

  const stop = useCallback(() => {
    if (timerRef.current) {
      window.clearTimeout(timerRef.current);
      timerRef.current = undefined;
    }
    setScrolling(false);
  }, []);

  /* 卸载时清理定时器，避免快速进出多条记录后残留 */
  useEffect(
    () => () => {
      if (timerRef.current) window.clearTimeout(timerRef.current);
    },
    [],
  );

  /* 动画总时长：滚动去程 + 末尾停顿 800ms + 回程，按距离换算 */
  const duration = overflowing ? distance / speed + 1.6 : 0;

  const style = {
    '--marquee-distance': `${distance}px`,
    '--marquee-duration': `${duration}s`,
  } as CSSProperties;

  return (
    <span
      ref={wrapRef}
      className={`marquee-title${scrolling ? ' is-scrolling' : ''}${overflowing ? ' is-overflowing' : ''}${className ? ` ${className}` : ''}`}
      onMouseEnter={start}
      onMouseLeave={stop}
      onFocus={start}
      onBlur={stop}
      style={style}
    >
      <span ref={innerRef} className="marquee-title__inner">
        {text}
      </span>
    </span>
  );
}

export default MarqueeTitle;
