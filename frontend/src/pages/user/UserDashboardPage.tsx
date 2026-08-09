import { useEffect, useMemo, useState } from 'react';
import { extractError } from '../../api/client';
import { myDashboardApi } from '../../api/resources';
import type { MyDashboard } from '../../api/types';
import { IconCalendar, IconChart, IconCheck, IconTrendUp } from '../../components/Icons';
import { useAuth } from '../../context/AuthContext';
import { useT } from '../../i18n';
import { BreakdownList } from './dashboard/BreakdownList';
import { KpiCard } from './dashboard/KpiCard';
import { RecentActivity } from './dashboard/RecentActivity';
import { StatusDonut } from './dashboard/StatusDonut';
import { TrendChart } from './dashboard/TrendChart';

const RANGE_OPTIONS: Array<{ days: number; key: 'week' | 'month' | 'quarter' }> = [
  { days: 7, key: 'week' },
  { days: 30, key: 'month' },
  { days: 90, key: 'quarter' },
];

export function UserDashboardPage() {
  const { t, lang } = useT();
  const { user } = useAuth();
  const [days, setDays] = useState(30);
  const [data, setData] = useState<MyDashboard | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setLoading(true);
    myDashboardApi.fetch(days)
      .then((d) => { setData(d); setError(null); })
      .catch((e) => setError(extractError(e)))
      .finally(() => setLoading(false));
  }, [days]);

  const greeting = useMemo(() => hourGreeting(lang, t), [lang, t]);
  const todayIso = useMemo(() => new Date().toISOString().slice(0, 10), []);

  if (loading && !data) {
    return <div className="udash-page"><div className="udash-panel">{t('common.loading')}</div></div>;
  }
  if (error) {
    return <div className="udash-page"><div className="alert alert-error">{error}</div></div>;
  }
  if (!data) return null;

  const streakLabel = data.currentStreak > 0
    ? t('user.dashboard.streakDays', { n: data.currentStreak })
    : t('user.dashboard.noStreak');

  const displayName = data.displayName || user?.displayName || user?.username || '';

  return (
    <div className="udash-page">
      {/* Hero */}
      <div className="udash-hero">
        <div>
          <p className="udash-hero-greet">
            {greeting}, {displayName} 👋
          </p>
          <p className="udash-hero-sub">{t('user.dashboard.subtitle')}</p>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', flexWrap: 'wrap' }}>
          <div className="udash-hero-streak">
            <span className="udash-hero-streak-flame">🔥</span>
            {streakLabel}
          </div>
          <div className="udash-range-picker" role="tablist">
            {RANGE_OPTIONS.map((opt) => (
              <button
                key={opt.days}
                type="button"
                className={days === opt.days ? 'active' : ''}
                onClick={() => setDays(opt.days)}
              >
                {t(`user.dashboard.range.${opt.key}`)}
              </button>
            ))}
          </div>
        </div>
      </div>

      {/* KPIs */}
      <div className="udash-kpi-grid">
        <KpiCard
          label={t('user.dashboard.kpi.today')}
          value={data.todayCount}
          sub={t('user.dashboard.kpi.todaySub')}
          icon={<IconCalendar size={18} />}
          accent="blue"
        />
        <KpiCard
          label={t('user.dashboard.kpi.week')}
          value={data.thisWeekCount}
          sub={t('user.dashboard.kpi.weekSub')}
          icon={<IconTrendUp size={18} />}
          accent="teal"
          trend={weekTrend(data, t)}
        />
        <KpiCard
          label={t('user.dashboard.kpi.month')}
          value={data.thisMonthCount}
          sub={t('user.dashboard.kpi.avgPerDay', { avg: data.averagePerDay.toFixed(2) })}
          icon={<IconChart size={18} />}
          accent="purple"
        />
        <KpiCard
          label={t('user.dashboard.kpi.total')}
          value={data.totalAllTime}
          sub={t('user.dashboard.kpi.bestDay', {
            n: data.bestDay.count,
            date: friendlyDate(data.bestDay.date, lang),
          })}
          icon={<IconCheck size={18} />}
          accent="green"
        />
      </div>

      {/* Trend + Donut */}
      <div className="udash-grid-2">
        <div className="udash-panel">
          <div className="udash-panel-head">
            <div>
              <div className="udash-panel-title">{t('user.dashboard.trendTitle')}</div>
              <div className="udash-panel-sub">
                {t('user.dashboard.trendSub', { n: data.daysWindow })}
              </div>
            </div>
            <div className="udash-panel-sub">
              🔥 {t('user.dashboard.longestStreak', { n: data.longestStreak })}
            </div>
          </div>
          <TrendChart data={data.daily} todayIso={todayIso} />
        </div>

        <div className="udash-panel">
          <div className="udash-panel-head">
            <div>
              <div className="udash-panel-title">{t('user.dashboard.statusTitle')}</div>
              <div className="udash-panel-sub">{t('user.dashboard.statusSub')}</div>
            </div>
          </div>
          <StatusDonut data={data.byStatus} />
        </div>
      </div>

      {/* Departments + Subcategories */}
      <div className="udash-grid-2-alt">
        <div className="udash-panel">
          <div className="udash-panel-head">
            <div>
              <div className="udash-panel-title">{t('user.dashboard.byDepartment')}</div>
              <div className="udash-panel-sub">{t('user.dashboard.byDepartmentSub')}</div>
            </div>
          </div>
          <BreakdownList items={data.byDepartment} />
        </div>

        <div className="udash-panel">
          <div className="udash-panel-head">
            <div>
              <div className="udash-panel-title">{t('user.dashboard.bySubcategory')}</div>
              <div className="udash-panel-sub">{t('user.dashboard.bySubcategorySub')}</div>
            </div>
          </div>
          <BreakdownList items={data.bySubcategory} />
        </div>
      </div>

      {/* Recent Activity */}
      <div className="udash-panel">
        <div className="udash-panel-head">
          <div>
            <div className="udash-panel-title">{t('user.dashboard.recentTitle')}</div>
            <div className="udash-panel-sub">{t('user.dashboard.recentSub')}</div>
          </div>
        </div>
        <RecentActivity items={data.recent} />
      </div>
    </div>
  );
}

function hourGreeting(lang: string, t: (k: string) => string): string {
  const h = new Date().getHours();
  if (h < 12) return t('user.dashboard.greetMorning');
  if (h < 18) return t('user.dashboard.greetAfternoon');
  return t('user.dashboard.greetEvening');
}

function weekTrend(data: MyDashboard, t: (k: string, p?: Record<string, string | number>) => string) {
  // Compare last 7 days sum vs previous 7 days sum from the daily series
  const daily = data.daily;
  if (daily.length < 14) return undefined;
  const last7 = daily.slice(-7).reduce((s, d) => s + d.count, 0);
  const prev7 = daily.slice(-14, -7).reduce((s, d) => s + d.count, 0);
  if (prev7 === 0 && last7 === 0) return undefined;
  if (prev7 === 0) return { dir: 'up' as const, text: t('user.dashboard.trendNew') };
  const delta = Math.round(((last7 - prev7) / prev7) * 100);
  if (delta > 0) return { dir: 'up' as const, text: `${delta}%` };
  if (delta < 0) return { dir: 'down' as const, text: `${delta}%` };
  return { dir: 'flat' as const, text: '0%' };
}

function friendlyDate(iso: string, lang: string): string {
  return new Date(iso).toLocaleDateString(lang === 'ar' ? 'ar-EG' : undefined, {
    day: 'numeric',
    month: 'short',
  });
}
