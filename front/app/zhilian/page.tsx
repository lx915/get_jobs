'use client'

import { useState, useEffect, useRef } from 'react'
import { createSSEWithBackoff } from '@/lib/sse'
import { BiLogOut, BiSave, BiBriefcase, BiPlay, BiStop, BiInfoCircle, BiBlock, BiTrash, BiPlus, BiBuilding } from 'react-icons/bi'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import AnalysisContent from '@/app/zhilian/analysis/AnalysisContent'
import PageHeader from '@/app/components/PageHeader'
import { API_BASE } from '@/lib/api'

interface ZhilianConfig {
  id?: number
  keywords?: string
  cityCode?: string
  // 薪资：库里存的是智联字典的区间代码（如 "25001,35000"）。
  // ⚠️ 代码里本身含逗号，所以不能和学历/公司人数共用 parseCodes（会被切成两段）。
  salary?: string
  // 学历 / 公司人数：对应智联新搜索页的 el / cs 参数，库里存逗号分隔的代码
  education?: string
  companySize?: string
  // 调试模式：1 = 只搜索采集，不真正点投递
  debugger?: number
}

interface Option { name: string; code: string }
interface ZhilianOptions { city: Option[]; salary: Option[]; education: Option[]; companySize: Option[] }

/** 黑名单条目：后端 boss_blacklist 表就是通用的 (type, value) 结构，智联用 zhilian_* 前缀的类型 */
interface BlacklistItem { id: number; type: string; value: string }

/**
 * 智联的三类黑名单。
 * 样式类名必须写全字面量，不能用 `bg-${color}-50` 拼 —— Tailwind 的扫描器看不到拼出来的类名。
 */
const BLACKLIST_GROUPS = [
  { type: 'zhilian_company', label: '公司', box: 'bg-orange-50 dark:bg-orange-950/20 border-orange-200 dark:border-orange-800' },
  { type: 'zhilian_job', label: '岗位', box: 'bg-blue-50 dark:bg-blue-950/20 border-blue-200 dark:border-blue-800' },
  { type: 'zhilian_recruiter', label: '招聘者', box: 'bg-purple-50 dark:bg-purple-950/20 border-purple-200 dark:border-purple-800' },
]

/** 智联字典里"不限"的薪资代码，等于不加筛选 */
const SALARY_ANY = '0000,9999999'
/** 旧版遗留的裸数字（如 "20000"）按"月薪下限 N 以上"解释，与后端 resolveSalaryCode 保持同一口径 */
const salaryCodeFromLegacyNumber = (n: string) => `${n},9999999`

/** 把库里存的逗号分隔代码解析成数组（兼容中文顿号/分号/括号写法） */
const parseCodes = (raw?: string): string[] => {
  if (!raw) return []
  return raw
    .replace(/[\[\]'"]/g, '')
    .replace(/[，、；;]/g, ',')
    .split(',')
    .map((s) => s.trim())
    .filter((s) => s.length > 0)
}

export default function ZhilianPage() {
  const [isLoggedIn, setIsLoggedIn] = useState(false)
  const [isDelivering, setIsDelivering] = useState(false)
  // 投递进度日志。
  // 后端一直在推进度（SSE /api/zhilian/stream），但此前前端**既没订阅这个流**，
  // isDelivering 除了"启动失败 / 用户点停止且后端返回成功"之外也没有任何地方会复位 ——
  // 于是点了「开始投递」界面毫无反应；任务已经结束了，按钮还永远停在「停止投递」。
  const [deliveryLogs, setDeliveryLogs] = useState<{ type: string; message: string; ts: number }[]>([])
  const deliveryLogRef = useRef<HTMLDivElement | null>(null)
  const [checkingLogin, setCheckingLogin] = useState(true)
  const [showLogoutDialog, setShowLogoutDialog] = useState(false)
  const [showSaveDialog, setShowSaveDialog] = useState(false)
  // title 可选：这个弹框既用于保存结果，也用于投递启动失败等提示
  const [saveResult, setSaveResult] = useState<{ success: boolean; message: string; title?: string } | null>(null)
  const [showLogoutResultDialog, setShowLogoutResultDialog] = useState(false)
  const [logoutResult, setLogoutResult] = useState<{ success: boolean; message: string } | null>(null)
  const [backendAvailable, setBackendAvailable] = useState(true)

  const [config, setConfig] = useState<ZhilianConfig>({ keywords: '', cityCode: '', salary: '' })
  const [options, setOptions] = useState<ZhilianOptions>({ city: [], salary: [], education: [], companySize: [] })
  const [loadingConfig, setLoadingConfig] = useState(true)

  // 薪资单选（存区间代码）、学历 / 公司人数多选（存代码）、调试模式开关
  const [selectedSalary, setSelectedSalary] = useState<string>('')
  const [selectedEducation, setSelectedEducation] = useState<string[]>([])
  const [selectedCompanySize, setSelectedCompanySize] = useState<string[]>([])
  const [debuggerMode, setDebuggerMode] = useState(false)

  // 黑名单：命中即「放弃投递」，不是搜索层过滤 —— 智联搜索参数里没有任何排除项。
  const [blacklist, setBlacklist] = useState<BlacklistItem[]>([])
  const [blacklistType, setBlacklistType] = useState('zhilian_company')
  const [newBlacklistKeyword, setNewBlacklistKeyword] = useState('')
  const [blacklistMsg, setBlacklistMsg] = useState('')

  useEffect(() => {
    if (typeof window === 'undefined' || typeof EventSource === 'undefined') {
      console.warn('[智联招聘] EventSource 不可用，无法连接SSE')
      setCheckingLogin(false)
      return
    }

    const client = createSSEWithBackoff(`${API_BASE}/api/jobs/login-status/stream`, {
      onOpen: () => console.log('[智联招聘 SSE] 连接已打开'),
      onError: (e, attempt, delay) => {
        console.warn(`[智联招聘 SSE] 连接错误，第${attempt}次重连，延迟 ${delay}ms`, e)
        setCheckingLogin(false)
      },
      listeners: [
        {
          name: 'connected',
          handler: (event) => {
            try {
              const data = JSON.parse(event.data)
              console.log('[智联招聘 SSE] connected事件数据:', data)
              console.log('[智联招聘 SSE] zhilianLoggedIn状态:', data.zhilianLoggedIn)
              setIsLoggedIn(data.zhilianLoggedIn || false)
              setCheckingLogin(false)
            } catch (error) {
              console.error('[智联招聘 SSE] 解析连接消息失败:', error)
            }
          },
        },
        {
          name: 'login-status',
          handler: (event) => {
            try {
              const data = JSON.parse(event.data)
              console.log('[智联招聘 SSE] login-status事件数据:', data)
              if (data.platform === 'zhilian') {
                console.log('[智联招聘 SSE] 智联登录状态变更:', data.isLoggedIn)
                setIsLoggedIn(data.isLoggedIn)
                setCheckingLogin(false)
              }
            } catch (error) {
              console.error('[智联招聘 SSE] 解析登录状态消息失败:', error)
            }
          },
        },
        { name: 'ping', handler: () => {} },
      ],
    })

    return () => client.close()
  }, [])

  // 订阅投递进度流：把后端推的每条进度显示出来，并在任务结束时把按钮复位
  useEffect(() => {
    if (typeof window === 'undefined' || typeof EventSource === 'undefined') {
      return
    }

    const client = createSSEWithBackoff(`${API_BASE}/api/zhilian/stream`, {
      onError: () => {
        // 断线由 createSSEWithBackoff 自己退避重连，这里不打扰用户
      },
      listeners: [
        { name: 'connected', handler: () => {} },
        {
          name: 'progress',
          handler: (event) => {
            try {
              const data = JSON.parse(event.data)
              const message = String(data.message ?? '')
              const type = String(data.type ?? 'info')
              setDeliveryLogs((prev) =>
                [...prev, { type, message, ts: Date.now() }].slice(-300),
              )
              // 终态必须复位，否则按钮会永远停在「停止投递」
              if (type === 'success' || type === 'error' || message.includes('用户取消投递')) {
                setIsDelivering(false)
              }
            } catch (error) {
              console.error('[智联招聘 SSE] 解析进度消息失败:', error)
            }
          },
        },
        { name: 'ping', handler: () => {} },
      ],
    })

    return () => client.close()
  }, [])

  // 刷新页面/重新进入后从后端同步真实运行状态，避免按钮与实际任务状态不一致
  useEffect(() => {
    fetch(`${API_BASE}/api/zhilian/status`)
      .then((response) => response.json())
      .then((data) => {
        if (typeof data?.isRunning === 'boolean') {
          setIsDelivering(data.isRunning)
        }
      })
      .catch(() => {})
  }, [])

  // 日志追加后自动滚到底部
  useEffect(() => {
    const box = deliveryLogRef.current
    if (box) {
      box.scrollTop = box.scrollHeight
    }
  }, [deliveryLogs])

  // 与猎聘一致的关键词解析/序列化
  const parseKeywordsFromDb = (raw?: string): string => {
    if (!raw) return ''
    const t = raw.trim()
    if (t.startsWith('[') && t.endsWith(']')) {
      try {
        const arr = JSON.parse(t)
        if (Array.isArray(arr)) return arr.filter(Boolean).join(', ')
      } catch (e) {
        console.warn('[智联] 解析关键词JSON失败，使用原值:', e)
      }
    }
    return t.replace(/，/g, ',')
  }

  const serializeKeywordsForDb = (display?: string): string => {
    const raw = (display || '').trim()
    if (!raw) return '[]'
    // 分隔符要认全：中文顿号「、」和分号「；」在国内输入习惯里非常常用。
    // 以前只把 , ， 当分隔符，"JAVA、后端、Spring" 会被存成**一个**关键词，
    // 投递时整串塞进搜索框，必然搜不到岗位。
    const norm = raw.replace(/[，、；;]/g, ',')
    const tokens = norm
      .split(',')
      .map((s) => s.trim())
      .filter((s) => s.length > 0)
    return JSON.stringify(tokens)
  }

  const fetchAllData = async () => {
    try {
      const res = await fetch(`${API_BASE}/api/zhilian/config`)
      const data = await res.json()
      if (data.config) {
        const normalized = { ...data.config }
        normalized.keywords = parseKeywordsFromDb(data.config.keywords)
        setConfig(normalized)
        setSelectedEducation(parseCodes(data.config.education))
        setSelectedCompanySize(parseCodes(data.config.companySize))
        setDebuggerMode(Number(data.config.debugger) === 1)
      }
      if (data.options) {
        // 薪资是单选，且库里存的可能是「字典区间代码」「中文档位名」或「旧版裸数字」。
        // 裸数字在这里补一个合成选项显示出来 —— 否则界面上是空的，
        // 用户一保存就被静默清掉，看起来像"薪资自己没了"。
        const salaryOptions: Option[] = [...(data.options.salary || [])]
        let salaryCode = ''
        const rawSalary = String(data.config?.salary ?? '').trim()
        if (rawSalary && rawSalary !== '0' && rawSalary !== '不限' && rawSalary !== SALARY_ANY) {
          const byCode = salaryOptions.find((o) => o.code === rawSalary)
          const byName = salaryOptions.find((o) => o.name === rawSalary)
          if (byCode) {
            salaryCode = byCode.code
          } else if (byName) {
            salaryCode = byName.code
          } else if (/^\d+$/.test(rawSalary)) {
            salaryCode = salaryCodeFromLegacyNumber(rawSalary)
            salaryOptions.push({ name: `月薪 ≥ ${rawSalary} 元（旧配置）`, code: salaryCode })
          } else if (/^\d+,\d+$/.test(rawSalary)) {
            // 后端会把旧裸数字迁移成区间码（"20000" → "20000,9999999"），
            // 这种码不在字典里。也要补一个选项显示出来，否则下拉框会回落到「不限」——
            // 看起来就像"薪资筛选条件自己丢了"，用户一保存就被清掉。
            salaryCode = rawSalary
            const lo = rawSalary.split(',')[0]
            salaryOptions.push({
              name: rawSalary.endsWith(',9999999')
                ? `月薪 ≥ ${lo} 元（旧配置）`
                : `自定义档位 ${rawSalary}`,
              code: salaryCode,
            })
          }
        }
        setSelectedSalary(salaryCode)
        setOptions({
          city: data.options.city || [],
          salary: salaryOptions,
          education: data.options.education || [],
          companySize: data.options.companySize || [],
        })
      }
    } catch (e) {
      console.error('[智联] 获取配置失败:', e)
    } finally {
      setLoadingConfig(false)
    }
  }

  // ==================== 黑名单 ====================
  // 只显示 zhilian_* 类型；同一张表里还有 Boss 的 company / job / recruiter，
  // 那批不能让智联页面看见（否则用户在智联页删掉一条，会连带把 Boss 的黑名单删了）。

  const fetchBlacklist = async () => {
    try {
      const res = await fetch(`${API_BASE}/api/blacklist`)
      if (!res.ok) return
      const all: BlacklistItem[] = await res.json()
      setBlacklist(all.filter((it) => (it.type || '').startsWith('zhilian_')))
    } catch (e) {
      console.error('[智联] 获取黑名单失败:', e)
    }
  }

  const handleAddBlacklist = async () => {
    const value = newBlacklistKeyword.trim()
    if (!value) return
    try {
      const res = await fetch(`${API_BASE}/api/blacklist`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ type: blacklistType, value }),
      })
      const data = await res.json()
      if (data?.success) {
        setNewBlacklistKeyword('')
        setBlacklistMsg('')
        await fetchBlacklist()
      } else {
        setBlacklistMsg(data?.message || '添加失败')
      }
    } catch (e) {
      console.error('[智联] 添加黑名单失败:', e)
      setBlacklistMsg('添加失败')
    }
  }

  const handleDeleteBlacklist = async (id: number) => {
    try {
      await fetch(`${API_BASE}/api/blacklist/${id}`, { method: 'DELETE' })
      await fetchBlacklist()
    } catch (e) {
      console.error('[智联] 删除黑名单失败:', e)
    }
  }

  useEffect(() => { fetchAllData(); fetchBlacklist() }, [])

  // 探测后端可用性（与 51job 保持一致风格）
  useEffect(() => {
    (async () => {
      try {
        const res = await fetch(`${API_BASE}/api/zhilian/config`, { method: 'GET' })
        const ok = !!res && res.ok
        setBackendAvailable(ok)
        if (ok) {
          await fetchAllData()
        } else {
          setLoadingConfig(false)
        }
      } catch (e) {
        setBackendAvailable(false)
        setLoadingConfig(false)
      }
    })()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const handleStartDelivery = async () => {
    try {
      setIsDelivering(true)
      const response = await fetch(`${API_BASE}/api/zhilian/start`, { method: 'POST' })
      const data = await response.json()
      if (data.success) {
        // 启动成功：不弹框，进度通过 SSE 推送
      } else {
        // 启动失败必须让用户看见。
        // 之前这里只有一句 setIsDelivering(false)，后端返回"请先登录智联招聘"
        // 或"任务已在运行中"时界面上一点提示都没有，看着就像按钮坏了。
        console.warn('[智联] 启动失败：', data.message)
        setSaveResult({ success: false, title: '启动失败', message: data.message || '启动投递失败，请查看后端日志' })
        setShowSaveDialog(true)
        setIsDelivering(false)
      }
    } catch (error) {
      console.error('[智联] 启动投递请求失败:', error)
      setSaveResult({
        success: false,
        title: '启动失败',
        message: `无法连接后端服务（${API_BASE}），请确认服务已启动`,
      })
      setShowSaveDialog(true)
      setIsDelivering(false)
    }
  }

  const handleStopDelivery = async () => {
    try {
      const response = await fetch(`${API_BASE}/api/zhilian/stop`, { method: 'POST' })
      const data = await response.json()
      if (!data.success) {
        // 后端说"没有正在运行的任务"——说明任务其实已经结束了。
        // 以前这里只在 success 时复位，于是任务跑完后再点停止，按钮一直卡在「停止投递」。
        console.warn('[智联] 停止返回：', data.message)
      }
    } catch (error) {
      console.error('[智联] 停止投递请求失败:', error)
    } finally {
      // 无论后端怎么说，都先把按钮复位：任务真的在跑的话，SSE 的终态消息也会再兜一次底
      setIsDelivering(false)
    }
  }

  const triggerLogout = async () => {
    try {
      const response = await fetch(`${API_BASE}/api/zhilian/logout`, { method: 'POST' })
      const data = await response.json()
      setIsLoggedIn(false)
      setIsDelivering(false)
      setLogoutResult({ success: data.success, message: data.success ? '已退出登录，Cookie已清空。' : data.message })
      setShowLogoutResultDialog(true)
    } catch (error) {
      setLogoutResult({ success: false, message: '退出登录失败：网络或服务异常。' })
      setShowLogoutResultDialog(true)
    }
  }

  const handleSaveCookie = async () => {
    try {
      const response = await fetch(`${API_BASE}/api/cookie/save?platform=zhilian`, { method: 'POST' })
      const data = await response.json()
      setSaveResult({ success: data.success, message: data.success ? '配置保存成功。' : data.message })
      setShowSaveDialog(true)
    } catch (error) {
      setSaveResult({ success: false, message: '配置保存失败：网络或服务异常。' })
      setShowSaveDialog(true)
    }
  }

  const handleSaveConfig = async () => {
    try {
      const payload = {
        ...config,
        keywords: serializeKeywordsForDb(config.keywords),
        // 薪资直接存区间代码（如 "25001,35000"），后端 resolveSalaryCode 认这种写法
        salary: selectedSalary,
        education: selectedEducation.join(','),
        companySize: selectedCompanySize.join(','),
        debugger: debuggerMode ? 1 : 0,
      }
      const response = await fetch(`${API_BASE}/api/zhilian/config`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      })
      if (response.ok) {
        try { await fetch(`${API_BASE}/api/cookie/save?platform=zhilian`, { method: 'POST' }) } catch {}
        await fetchAllData()
        setSaveResult({ success: true, message: '保存成功，配置已更新。' })
      } else {
        setSaveResult({ success: false, message: '保存失败：后端返回异常状态。' })
      }
      setShowSaveDialog(true)
    } catch (error) {
      console.error('[智联] 保存配置失败:', error)
      setSaveResult({ success: false, message: '保存失败：网络或服务异常。' })
      setShowSaveDialog(true)
    }
  }

  return (
    <div className="space-y-6">
      <PageHeader
        icon={<BiBriefcase className="text-2xl" />}
        title="智联招聘配置"
        subtitle="配置智联招聘平台的求职参数"
        iconClass="text-white"
        accentBgClass="bg-purple-500"
        actions={
          <div className="flex items-center gap-2">
            {checkingLogin ? (
              <Button size="sm" disabled className="rounded-full bg-gray-300 text-gray-600 cursor-not-allowed px-4 shadow">
                <BiPlay className="mr-1" /> 检查登录中...
              </Button>
            ) : !isLoggedIn ? (
              <Button size="sm" disabled className="rounded-full bg-gray-300 text-gray-600 cursor-not-allowed px-4 shadow">
                <BiPlay className="mr-1" /> 请先登录智联招聘
              </Button>
            ) : isDelivering ? (
              <Button onClick={handleStopDelivery} size="sm" className="rounded-full bg-gradient-to-r from-red-500 to-rose-600 hover:from-red-600 hover:to-rose-700 text-white px-4 shadow-lg hover:shadow-xl transition-all duration-300 hover:scale-105">
                <BiStop className="mr-1" /> 停止投递
              </Button>
            ) : (
              <Button onClick={handleStartDelivery} size="sm" className="rounded-full bg-gradient-to-r from-teal-500 to-green-500 hover:from-teal-600 hover:to-green-600 text-white px-4 shadow-lg hover:shadow-xl transition-all duration-300 hover:scale-105">
                <BiPlay className="mr-1" /> 开始投递
              </Button>
            )}
            <Button onClick={() => setShowLogoutDialog(true)} size="sm" className="rounded-full bg-gradient-to-r from-red-500 to-pink-500 hover:from-red-600 hover:to-pink-600 text-white px-4 shadow-lg hover:shadow-xl transition-all duration-300 hover:scale-105">
              <BiLogOut className="mr-1" /> 退出登录
            </Button>
            <Button onClick={handleSaveConfig} size="sm" className="rounded-full bg-gradient-to-r from-blue-500 to-indigo-500 hover:from-blue-600 hover:to-indigo-600 text-white px-4 shadow-lg hover:shadow-xl transition-all duration-300 hover:scale-105">
              <BiSave className="mr-1" /> 保存配置
            </Button>
          </div>
        }
      />

      {/* 投递进度面板：刻意放在 Tabs **之前**。
          放在「平台配置」页里的话，用户切到「投递分析」点完按钮就什么都看不到，
          会误以为程序没反应 —— 这正是智联模块原来的表现。 */}
      {(isDelivering || deliveryLogs.length > 0) && (
        <Card className="animate-in fade-in duration-500">
          <CardHeader className="pb-3">
            <CardTitle className="flex items-center gap-2 text-base">
              <BiInfoCircle className="text-primary" />
              投递进度
              {isDelivering ? (
                <span className="text-xs font-normal text-purple-600">进行中…</span>
              ) : (
                <span className="text-xs font-normal text-muted-foreground">已结束</span>
              )}
            </CardTitle>
          </CardHeader>
          <CardContent>
            <div
              ref={deliveryLogRef}
              className="max-h-64 space-y-1 overflow-y-auto rounded-md border bg-muted/30 p-3"
            >
              {deliveryLogs.length === 0 ? (
                <p className="text-sm text-muted-foreground">等待任务输出…</p>
              ) : (
                deliveryLogs.map((log, i) => (
                  <div
                    key={i}
                    className={`text-sm ${
                      log.type === 'success'
                        ? 'text-green-600'
                        : log.type === 'error'
                          ? 'text-red-600'
                          : log.type === 'warning'
                            ? 'text-amber-600'
                            : 'text-foreground'
                    }`}
                  >
                    <span className="text-muted-foreground">
                      [{new Date(log.ts).toLocaleTimeString()}]
                    </span>{' '}
                    {log.message}
                  </div>
                ))
              )}
            </div>
            <div className="mt-2 flex justify-end">
              <Button variant="ghost" size="sm" onClick={() => setDeliveryLogs([])}>
                清空日志
              </Button>
            </div>
          </CardContent>
        </Card>
      )}

      <Tabs defaultValue="config" className="w-full">
        <TabsList className="grid w-full grid-cols-2">
          <TabsTrigger value="config">平台配置</TabsTrigger>
          <TabsTrigger value="analytics">投递分析</TabsTrigger>
        </TabsList>

        <TabsContent value="config" className="space-y-6 mt-6">
          <Card className="animate-in fade-in slide-in-from-bottom-5 duration-700">
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <BiBriefcase className="text-primary" />
                智联招聘平台说明
              </CardTitle>
            </CardHeader>
            <CardContent>
              <div className="space-y-4">
                <p className="text-sm text-muted-foreground">请在浏览器标签页中登录智联招聘平台，登录成功后系统会自动检测登录状态。</p>
                <p className="text-sm text-muted-foreground">登录成功后，点击"开始投递"按钮启动自动投递任务。</p>
                <p className="text-sm text-muted-foreground">点击"保存配置"按钮可手动保存当前登录相关信息到数据库。</p>
              </div>
            </CardContent>
          </Card>

          {/* 配置表单 */}
          <Card className="animate-in fade-in slide-in-from-bottom-5 duration-700">
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <BiBriefcase className="text-primary" />
                配置参数
              </CardTitle>
            </CardHeader>
            <CardContent>
              {loadingConfig ? (
                <p className="text-sm text-muted-foreground">配置加载中...</p>
              ) : (
                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  <div className="space-y-2">
                    <Label>搜索关键词（逗号分隔）</Label>
                    <Input
                      placeholder="如：Java, 后端, Spring"
                      value={config.keywords || ''}
                      onChange={(e) => setConfig((c) => ({ ...c, keywords: e.target.value }))}
                    />
                  </div>
                  <div className="space-y-2">
                    <Label>城市</Label>
                    <Select
                      value={config.cityCode || ''}
                      onChange={(e) => setConfig((c) => ({ ...c, cityCode: e.target.value }))}
                      placeholder="请选择城市"
                    >
                      {options.city.map((o) => (
                        <option key={o.code} value={o.code}>{o.name}</option>
                      ))}
                    </Select>
                  </div>
                  <div className="space-y-2">
                    <Label>薪资范围（单选，不限则留空）</Label>
                    <Select
                      value={selectedSalary}
                      onChange={(e) => setSelectedSalary(e.target.value)}
                      placeholder="不限"
                    >
                      <option value="">不限</option>
                      {options.salary.map((o) => (
                        <option key={o.code} value={o.code}>{o.name}</option>
                      ))}
                    </Select>
                  </div>
                  <div className="space-y-2">
                    <Label>学历（可多选，不限则留空）</Label>
                    <MultiSelect
                      options={options.education}
                      selected={selectedEducation}
                      onChange={setSelectedEducation}
                      placeholder="不限"
                    />
                  </div>
                  <div className="space-y-2">
                    <Label>公司人数（可多选，不限则留空）</Label>
                    <MultiSelect
                      options={options.companySize}
                      selected={selectedCompanySize}
                      onChange={setSelectedCompanySize}
                      placeholder="不限"
                    />
                  </div>
                  <div className="space-y-2 md:col-span-2">
                    <Label>调试模式</Label>
                    <label className="flex cursor-pointer items-center gap-2 text-sm text-muted-foreground">
                      <input
                        type="checkbox"
                        className="h-4 w-4 cursor-pointer"
                        checked={debuggerMode}
                        onChange={(e) => setDebuggerMode(e.target.checked)}
                      />
                      只搜索采集、不真正投递（用于验证关键词与筛选条件，勾选后点「开始投递」不会投出任何简历）
                    </label>
                  </div>
                </div>
              )}
            </CardContent>
          </Card>

          {/* 黑名单管理：命中即「放弃投递」（不点投递），不是搜索层过滤 */}
          <Card className="animate-in fade-in slide-in-from-bottom-5 duration-700">
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <BiBlock className="text-primary" />
                黑名单管理（{blacklist.length} 条）
              </CardTitle>
            </CardHeader>
            <CardContent>
              <p className="mb-4 text-sm text-muted-foreground">
                命中的岗位<b>不会被投递</b>，并在投递记录里标成「已过滤」。
                匹配方式是<b>子串包含且忽略大小写</b>（填「华为」能拦住「华为技术有限公司」，
                填「PDD」也能拦住「pdd科技」）。智联的搜索参数里没有排除项，所以只能用这种方式实现。
              </p>
              <div className="flex gap-2">
                <Select
                  value={blacklistType}
                  onChange={(e) => setBlacklistType(e.target.value)}
                  className="w-32"
                >
                  <option value="zhilian_company">公司</option>
                  <option value="zhilian_job">岗位</option>
                  <option value="zhilian_recruiter">招聘者</option>
                </Select>
                <Input
                  value={newBlacklistKeyword}
                  onChange={(e) => setNewBlacklistKeyword(e.target.value)}
                  placeholder={
                    blacklistType === 'zhilian_company'
                      ? '输入公司名称关键词'
                      : blacklistType === 'zhilian_job'
                        ? '输入岗位关键词'
                        : '输入招聘者职位关键词（如：猎头）'
                  }
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') handleAddBlacklist()
                  }}
                />
                <Button onClick={handleAddBlacklist} className="whitespace-nowrap">
                  <BiPlus />
                  添加
                </Button>
              </div>
              {blacklistMsg && <p className="mt-2 text-sm text-amber-600">{blacklistMsg}</p>}

              <div className="mt-6 space-y-4">
                {BLACKLIST_GROUPS.map((group) => {
                  const items = blacklist.filter((it) => it.type === group.type)
                  return (
                    <div key={group.type}>
                      <h3 className="mb-2 flex items-center gap-2 text-sm font-semibold">
                        {group.type === 'zhilian_company' ? (
                          <BiBuilding className="text-orange-500" />
                        ) : (
                          <BiBriefcase className="text-blue-500" />
                        )}
                        <span>
                          {group.label}黑名单（{items.length}）
                        </span>
                      </h3>
                      {items.length === 0 ? (
                        <div className="rounded-lg bg-muted/30 py-3 text-center text-xs text-muted-foreground">
                          暂无{group.label}黑名单
                        </div>
                      ) : (
                        <div className="space-y-2">
                          {items.map((item) => (
                            <div
                              key={item.id}
                              className={`flex items-center justify-between rounded-lg border p-3 ${group.box}`}
                            >
                              <span className="text-sm">{item.value}</span>
                              <Button
                                variant="ghost"
                                size="sm"
                                onClick={() => handleDeleteBlacklist(item.id)}
                                className="text-red-500 hover:bg-red-50 hover:text-red-700"
                              >
                                <BiTrash />
                              </Button>
                            </div>
                          ))}
                        </div>
                      )}
                    </div>
                  )
                })}
              </div>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="analytics" className="space-y-6 mt-6">
          <AnalysisContent />
        </TabsContent>
      </Tabs>

      {/* 退出确认弹框 */}
      {showLogoutDialog && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
          <Card className="bg-white dark:bg-neutral-900 rounded-2xl shadow-2xl w-[92%] max-w-sm border-0">
            <CardHeader className="pb-2">
              <CardTitle className="text-lg flex items-center gap-2">
                <BiLogOut className="text-red-500" /> 确认退出登录
              </CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-sm text-muted-foreground mb-4">退出后将清除Cookie并切换为未登录状态。</p>
              <div className="flex justify-end gap-2">
                <Button variant="ghost" onClick={() => setShowLogoutDialog(false)} className="rounded-full px-4">取消</Button>
                <Button onClick={async () => { await triggerLogout(); setShowLogoutDialog(false) }} className="rounded-full bg-gradient-to-r from-red-500 to-rose-600 text-white px-4">确认退出</Button>
              </div>
            </CardContent>
          </Card>
        </div>
      )}

      {/* 退出登录结果弹框 */}
      {showLogoutResultDialog && logoutResult && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30">
          <Card className="bg-white dark:bg-neutral-900 rounded-2xl shadow-2xl w-[92%] max-w-sm border-0">
            <CardHeader className="pb-2">
              <CardTitle className="text-lg flex items-center gap-2">
                <BiLogOut className={logoutResult.success ? 'text-green-500' : 'text-red-500'} />
                {logoutResult.success ? '退出登录成功' : '退出登录失败'}
              </CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-sm text-muted-foreground mb-4">{logoutResult.message}</p>
              <Button onClick={() => setShowLogoutResultDialog(false)} className={`rounded-full px-4 ${logoutResult.success ? 'bg-green-500' : 'bg-red-500'} text-white`}>知道了</Button>
            </CardContent>
          </Card>
        </div>
      )}

      {/* 保存Cookie结果弹框（也用于投递启动失败等提示） */}
      {showSaveDialog && saveResult && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30">
          <Card className="bg-white dark:bg-neutral-900 rounded-2xl shadow-2xl w-[92%] max-w-sm border-0">
            <CardHeader className="pb-2">
              <CardTitle className="text-lg flex items-center gap-2">
                <BiSave className={saveResult.success ? 'text-green-500' : 'text-red-500'} />
                {saveResult.title || (saveResult.success ? '保存成功' : '保存失败')}
              </CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-sm text-muted-foreground mb-4">{saveResult.message}</p>
              <Button onClick={() => setShowSaveDialog(false)} className={`rounded-full px-4 ${saveResult.success ? 'bg-green-500' : 'bg-red-500'} text-white`}>知道了</Button>
            </CardContent>
          </Card>
        </div>
      )}
    </div>
  )
}

/**
 * 智联用的多选下拉：按代码选择、显示中文名。
 * 与 boss 页的 MultiSelect 保持同样的交互（点击外部/ESC 关闭、函数式更新），
 * 但不走 portal —— 智联这个表单位于页面顶部，直接用 absolute 覆盖即可，
 * 少一层 portal 就少一类定位问题。
 */
function MultiSelect({
  options,
  selected,
  onChange,
  placeholder,
}: {
  options: Option[]
  selected: string[]
  // 必须用函数式更新：连续快速点击时若基于 props 里的旧 selected 计算，会丢掉前一次勾选
  onChange: (updater: (prev: string[]) => string[]) => void
  placeholder?: string
}) {
  const [open, setOpen] = useState(false)
  const wrapperRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const onDocMouseDown = (e: MouseEvent) => {
      if (!wrapperRef.current?.contains(e.target as Node)) setOpen(false)
    }
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onDocMouseDown)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('mousedown', onDocMouseDown)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [open])

  const toggle = (code: string) => {
    onChange((prev) => (prev.includes(code) ? prev.filter((c) => c !== code) : [...prev, code]))
  }

  const selectedNames = options.filter((o) => selected.includes(o.code)).map((o) => o.name)

  return (
    <div className="relative" ref={wrapperRef}>
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="flex h-10 w-full items-center justify-between rounded-md border border-input bg-background px-3 py-2 text-sm shadow-sm transition-colors hover:bg-accent focus:outline-none focus:ring-2 focus:ring-ring"
      >
        <span className={`truncate ${selectedNames.length === 0 ? 'text-muted-foreground' : ''}`}>
          {selectedNames.length > 0 ? selectedNames.join('，') : placeholder || '请选择'}
        </span>
        <span className={`ml-2 text-xs transition-transform duration-200 ${open ? 'rotate-180' : ''}`}>▼</span>
      </button>
      {open && (
        <div className="absolute z-50 mt-1 max-h-56 w-full overflow-y-auto rounded-md border bg-white p-1 shadow-lg dark:bg-neutral-900">
          {options.length === 0 ? (
            <div className="px-2 py-1.5 text-sm text-muted-foreground">暂无可选项</div>
          ) : (
            options.map((opt) => {
              const checked = selected.includes(opt.code)
              return (
                <div
                  key={opt.code}
                  onClick={() => toggle(opt.code)}
                  className={`flex cursor-pointer items-center gap-2 rounded px-2 py-1.5 text-sm hover:bg-accent ${
                    checked ? 'font-medium' : ''
                  }`}
                >
                  <span
                    className={`inline-flex h-4 w-4 items-center justify-center rounded border text-[10px] ${
                      checked ? 'border-teal-500 bg-teal-500 text-white' : 'border-muted-foreground/40'
                    }`}
                  >
                    {checked ? '✓' : ''}
                  </span>
                  <span className="truncate">{opt.name}</span>
                </div>
              )
            })
          )}
        </div>
      )}
    </div>
  )
}
