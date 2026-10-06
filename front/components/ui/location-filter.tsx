"use client"

import * as React from "react"

import { Input } from "@/components/ui/input"
import { API_BASE } from "@/lib/api"

type LocationOption = { name: string; count: number }

export interface LocationFilterProps {
  /** 平台标识，对应后端 GET /api/{platform}/locations */
  platform: "boss" | "zhilian" | "51job" | "liepin"
  value: string
  onChange: (value: string) => void
  placeholder?: string
  id?: string
  className?: string
}

/**
 * 地点筛选：可自由输入，也可从「库里真实存在的地点」里点选。
 *
 * 为什么不是纯文本输入框：四个平台的地点粒度并不统一 —— Boss 存「北京」，
 * 智联存「北京·海淀区」。用户盯着输入框猜不到库里到底是什么格式，
 * 之前只能靠一次次试。这里把 /locations 的真实值（带岗位数）做成 datalist，
 * 同时**保留自由输入**：后端是按「包含」匹配的，手打「北京」能命中所有北京区县。
 */
export function LocationFilter({
  platform,
  value,
  onChange,
  placeholder = "城市，可输入或下拉选",
  id,
  className,
}: LocationFilterProps) {
  const [options, setOptions] = React.useState<LocationOption[]>([])
  // useId 生成的 id 带冒号，剥掉后再拼，避免个别场景下选择器解析异常
  const listId = `loc-list-${React.useId().replace(/:/g, "")}`

  React.useEffect(() => {
    let alive = true
    fetch(`${API_BASE}/api/${platform}/locations`)
      .then((r) => (r.ok ? r.json() : []))
      .then((data) => {
        if (!alive || !Array.isArray(data)) return
        setOptions(
          data
            .filter((d: { name?: unknown }) => d && typeof d.name === "string" && d.name.trim() !== "")
            .map((d: { name: string; count?: number }) => ({
              name: String(d.name),
              count: Number(d.count) || 0,
            }))
        )
      })
      .catch(() => {
        // 选项拉取失败不影响自由输入，静默降级为普通输入框
      })
    return () => {
      alive = false
    }
  }, [platform])

  return (
    <>
      <Input
        id={id}
        list={listId}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        className={className}
        autoComplete="off"
      />
      <datalist id={listId}>
        {options.map((o) => (
          <option key={o.name} value={o.name}>{`${o.name}（${o.count}）`}</option>
        ))}
      </datalist>
    </>
  )
}
