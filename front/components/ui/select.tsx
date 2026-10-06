import * as React from "react"
import { createPortal } from "react-dom"
import { cn } from "@/lib/utils"

type OptionItem = { value: string; label: React.ReactNode }

export interface SelectProps {
  value?: string
  onChange?: (e: { target: { value: string } }) => void
  placeholder?: string
  className?: string
  id?: string
  disabled?: boolean
  children?: React.ReactNode
}

const Select = React.forwardRef<HTMLDivElement, SelectProps>(
  ({ className, children, value, onChange, placeholder, disabled, id, ...props }, ref) => {
    const [open, setOpen] = React.useState(false)
    const [mounted, setMounted] = React.useState(false)
    const wrapperRef = React.useRef<HTMLDivElement>(null)
    const buttonRef = React.useRef<HTMLButtonElement>(null)
    const dropdownRef = React.useRef<HTMLDivElement>(null)
    const [dropdownPosition, setDropdownPosition] = React.useState({ top: 0, left: 0, width: 0 })
    // 首次测量完成前先隐藏面板，避免在 (0,0) 处闪一帧（visibility:hidden 仍可测量高度）
    const [positioned, setPositioned] = React.useState(false)

    // 确保组件已挂载（解决 SSR 问题）
    React.useEffect(() => {
      setMounted(true)
    }, [])

    const options = React.useMemo<OptionItem[]>(() => {
      return React.Children.toArray(children)
        .filter((c) => React.isValidElement(c) && (c as any).type === 'option')
        .map((c: any) => ({ value: String(c.props.value ?? c.props.children), label: c.props.children }))
    }, [children])

    const selected = options.find((o) => String(value ?? '') === String(o.value))

    const emitChange = (val: string) => onChange?.({ target: { value: val } } as any)

    // 计算下拉框位置：下方放得下就向下弹；下方放不下且上方更宽裕时向上翻转。
    // 之前只按「按钮底部 + 8px」算，而分页条、表格下方的表单都贴在视口底部，
    // 面板（最高 224px，见 globals.css 的 .dropdown-panel max-h-56）会整块溢出
    // 视口底部 —— 用户看不见、点不到「每页」的候选项，表现成「选了看不到 size」。
    const updatePosition = React.useCallback(() => {
      const btn = buttonRef.current
      if (!btn) return
      const rect = btn.getBoundingClientRect()
      // 面板已被 max-h-56 限制在 224px 以内，这里再夹一次，测量失败时用上限兜底
      const PANEL_MAX = 224
      const GAP = 8
      const panelHeight = Math.min(dropdownRef.current?.offsetHeight || PANEL_MAX, PANEL_MAX)
      const spaceBelow = window.innerHeight - rect.bottom - GAP
      const spaceAbove = rect.top - GAP
      const openUp = spaceBelow < panelHeight && spaceAbove > spaceBelow
      // 向下弹时再夹一次上边界，保证「下方略不够、上方也不宽裕」时面板仍完整可见
      const maxTop = Math.max(GAP, window.innerHeight - panelHeight - GAP)
      setDropdownPosition({
        top: openUp
          ? Math.max(GAP, rect.top - panelHeight - GAP)
          : Math.min(rect.bottom + GAP, maxTop),
        left: rect.left,
        width: rect.width,
      })
      setPositioned(true)
    }, [])

    // 打开时计算位置
    React.useEffect(() => {
      if (open) {
        updatePosition()
        // 监听滚动和窗口大小变化，更新位置
        const handleUpdate = () => updatePosition()
        window.addEventListener('scroll', handleUpdate, true)
        window.addEventListener('resize', handleUpdate)
        return () => {
          window.removeEventListener('scroll', handleUpdate, true)
          window.removeEventListener('resize', handleUpdate)
        }
      }
      // 关闭时复位，下次打开重新测量
      setPositioned(false)
    }, [open, updatePosition])

    // 点击外部关闭下拉框
    React.useEffect(() => {
      const handleClickOutside = (event: MouseEvent) => {
        const target = event.target as Node
        // 检查点击是否在按钮或下拉框内
        const clickedButton = wrapperRef.current?.contains(target)
        const clickedDropdown = dropdownRef.current?.contains(target)

        if (!clickedButton && !clickedDropdown) {
          setOpen(false)
        }
      }

      const handleEscape = (event: KeyboardEvent) => {
        if (event.key === 'Escape') {
          setOpen(false)
        }
      }

      if (open) {
        // 使用 setTimeout 确保 DOM 已更新
        setTimeout(() => {
          document.addEventListener('mousedown', handleClickOutside)
          document.addEventListener('keydown', handleEscape)
        }, 0)
      }

      return () => {
        document.removeEventListener('mousedown', handleClickOutside)
        document.removeEventListener('keydown', handleEscape)
      }
    }, [open])

    return (
      <div ref={ref} {...props}>
        <div ref={wrapperRef} className="relative">
          <button
            ref={buttonRef}
            id={id as string}
            type="button"
            disabled={disabled}
            onClick={() => setOpen((v) => !v)}
            className={cn(
              "flex h-10 w-full rounded-full px-4 py-2 text-sm pr-8",
              "border border-white/40 bg-white/5 shadow-[inset_0_1px_0_rgba(255,255,255,.25)]",
              "transition-all duration-200 hover:bg-white/10 hover:shadow-md",
              disabled ? "cursor-not-allowed opacity-50" : "focus:outline-none focus:ring-2 focus:ring-emerald-400/40 focus:border-emerald-300/60",
              // 自定义箭头（浅灰）
              "bg-[url('data:image/svg+xml;utf8,<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"%23a1a1aa\" stroke-width=\"2\"><path d=\"M6 9l6 6 6-6\"/></svg>')] bg-no-repeat bg-[length:16px_16px] bg-[position:right_12px_center]",
              className
            )}
          >
            <span className="truncate text-sm">{selected ? selected.label : (placeholder ?? '')}</span>
          </button>

          {open && mounted && createPortal(
            <div
              ref={dropdownRef}
              className="dropdown-panel"
              style={{
                top: `${dropdownPosition.top}px`,
                left: `${dropdownPosition.left}px`,
                width: `${dropdownPosition.width}px`,
                // 测量完成前不显示，避免在 (0,0) 处闪现一帧
                visibility: positioned ? 'visible' : 'hidden',
              }}
            >
              <ul className="py-1">
                {options.map((o) => {
                  const active = String(value ?? '') === String(o.value)
                  return (
                    <li
                      key={String(o.value)}
                      className={cn(
                        "group flex items-center justify-between gap-3 px-3 py-2 cursor-pointer transition-all border-b border-white/12 last:border-b-0",
                        active ? "bg-gradient-to-r from-emerald-500/12 to-cyan-500/12" : "hover:bg-white/12"
                      )}
                      onClick={() => {
                        emitChange(String(o.value))
                        setOpen(false)
                      }}
                    >
                      <span className="flex items-center gap-3">
                        <span className={cn("inline-flex h-4 w-4 items-center justify-center rounded-md border border-white/30 bg-white/10 shadow-inner transition-all", active && "bg-emerald-400/60 border-emerald-300/80")}></span>
                        <span className="text-sm truncate">{o.label}</span>
                      </span>
                    </li>
                  )
                })}
              </ul>
            </div>,
            document.body
          )}
        </div>
      </div>
    )
  }
)
Select.displayName = "Select"

export { Select }
