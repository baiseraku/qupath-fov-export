---
name: qupath-fov-export
description: >-
  用 QuPath 0.7 批量导出固定尺寸、固定放大倍数的视野（FOV）：在切片上手工摆好选框后，
  一次把所有框导成图片，尺寸严格一致、文件名带坐标与 µm/px，并生成 CSV 清单。
  解决 QuPath 自带导出一次只能导一个区域（选中多个会导成外接框合成图）的问题。
  含放大倍数换算、TIFF 压缩方式的实测体积对比，以及若干 Groovy/QuPath 坑。
whenToUse: >-
  用户提出「把选好的视野导出来」「批量导出这几个框」「每个视野导一张图」「按 40x 导出视野」
  「固定尺寸导出区域」「导出的图大小要一样」时。也适用于为组间比较准备等大视野图的场景。
metadata:
  version: 1.0.0
  source: 实操沉淀（固定尺寸视野批量导出端到端跑通；含体积对比与 --args 空格坑）
disable-model-invocation: false
---

# QuPath 固定尺寸视野批量导出

在**已建好的 QuPath 项目**上，把手工摆好的选框批量导成尺寸完全一致的图片。
全程无头，也可以直接在脚本编辑器里跑。

前置：切片已注册进 `.qpproj`，并且每张图都有像素标定（`PhysicalSizeX`，即 MPP）。

## 环境事实

- QuPath **0.7.0**，自带 JRE，**不需要系统装 Java**
- `-p` 必须给**绝对路径**，相对路径会报 `this.dirBase is null`
- `QuPath script -p 项目 -s 脚本` 会对项目里**每张图各跑一次**脚本
- 脚本里用 `getCurrentImageData()` / `getProjectEntry()` 取当前图像，不要依赖 `imageData` 这个绑定（命令行下不一定存在）

## 摆框（这一步是手工的）

关键：**框的尺寸必须完全一致**，鼠标拖是拖不准的。

`Objects ▸ Specify annotation…`：
- Width / Height 填 `BOX_PX`，**单位选 px**（选 µm 会因四舍五入差几像素）
- **X、Y 留空** → 框自动落在当前视野正中心（官方 tooltip：*if missing or < 0, annotation will be centered in current viewer*）
- 勾 Lock 可防止误拖

移动框：工具栏 **Move** 工具，在框内部拖动；拖控制点会改尺寸，别碰。
被锁住按 `Cmd + K` 切换锁定。位置不满意就删掉重画，比拖动准。

## 一条命令跑完

```bash
QP=<QuPath 可执行文件>
S=scripts/export_fovs.groovy

"$QP" script -p /abs/项目/project.qpproj -s "$S"
# 覆盖参数：--args "[downsample, 框边长px, 输出目录]"
"$QP" script -p /abs/项目/project.qpproj -s "$S" --args "[2.0, 4000, /abs/输出目录]"
```

## 参数

| 参数 | 默认 | 说明 |
|---|---|---|
| `DOWNSAMPLE` | 2.0 | 降采样倍数，决定导出后的 µm/px 和等效倍数 |
| `BOX_PX` | 4000 | 只导这个边长的正方形框；0 = 导所有非整图标注 |
| `OUT_DIR` | `~/Downloads/QuPath_FOV` | 不存在自动新建，已存在直接用 |
| `EXT` | `tif` | `tif` / `png` / `jpg` |
| `TIFF_COMPRESSION` | `LZW` | `LZW` / `Deflate` / `None` |
| `WRITE_MANIFEST` | true | 是否写 CSV 清单 |

## 尺寸与放大倍数（动手前先算）

```
导出后 µm/px = MPP × downsample
输出像素宽   = round(区域像素宽 / downsample)
等效倍数     ≈ 10 / (MPP × downsample)      # 40x ≈ 0.25 µm/px
```

**downsample 不改变视野范围**，只决定像素密度。倍数和输出像素数一旦定死，视野大小就唯一确定。

常见诉求的解法：

- 要**原生金字塔层**（最锐、最快）：downsample 取 1 / 2 / 4 / 8 / 16 / 32 / 64
- 要**严格 40x**：downsample = 0.25 / MPP（不是 2 的幂，会从最近的金字塔层插值，画质损失很小）
- 要"40x 且输出 2000 px"：框边长 = 2000 × downsample

模拟倍数只是数字切片的换算值，图注写 `equivalent to 40x` 或直接标 scale bar / µm per pixel 更严谨。

## 输出格式与体积（同一块 2000×2000 区域实测）

| 写法 | 体积 | 耗时 | 无损 |
|---|---:|---:|:--:|
| `.tif`（QuPath 默认 writer） | 11.44 MB | 37 ms | 是 |
| `.tif` + LZW | 2.30 MB | 194 ms | 是 |
| `.tif` + Deflate | 1.45 MB | 737 ms | 是 |
| `.png` | 1.68 MB | 138 ms | 是 |
| `.jpg` | 0.14 MB | 54 ms | **否**（抽样 45% 像素不同） |

**`ImageWriterTools.writeImageRegion(server, req, "x.tif")` 写出来是不压缩的**。
要压缩必须自己拿 `ImageIO` 的 TIFF writer 设 `ImageWriteParam`——脚本里就是这么做的。

- 默认 **LZW**：无损、几乎所有软件都认（ImageJ / Fiji / Photoshop / 投稿系统）
- `Deflate`：无损且更小，写入慢约 4 倍，个别老软件读不了 ZIP 压缩的 TIFF
- `None`：最原始，45 张 500 MB+

## 关键设计：按尺寸筛选框

只导边长等于 `BOX_PX` 的框，原因是项目里通常本来就有一个**覆盖整张切片**的组织标注
（实测 10 万×7 万像素量级）。无脑导全部标注会试图导出整张切片，直接把内存吃爆。

按尺寸筛选还顺带保证所有输出的视野严格同尺寸——这是组间比较的前提。
没匹配到任何框时脚本会打印现有标注的实际尺寸，便于排查。

排序按 y 再 x（从上到下、从左到右），编号在不同切片上一致。

## Groovy / QuPath API 坑（实测踩过，照抄可避）

- **`--args` 逗号后的空格会保留**。`--args "[2.0, 4000, /path]"` 取到的是 `" /path"`，
  不 trim 就会变成一个以空格开头的相对路径，`mkdirs()` 会老老实实建出一串奇怪目录。
- **`String.format` 的 `%f` 不接受 Long**。`System.currentTimeMillis() - t0` 是 Long，
  用 `%.0f` 会抛 `IllegalFormatConversionException: f != java.lang.Long`，用 `%d`。
- 输出尺寸是 `round(区域 / downsample)`：区域 8000、ds=13 → 615 px（不是 616）。
- `RegionRequest.createInstance(String path, double downsample, ROI roi)` 可以直接传 ROI，
  比手算 x/y/w/h 省事。
- 项目脚本要出现在 `Automate ▸ Project scripts…` 里，必须放在 `<项目目录>/scripts/`；
  想跨项目共用，用 `Automate ▸ Set script directory…` 指定一个固定目录，
  之后从 `Automate ▸ Shared scripts…` 访问。

## 输出文件

- 图片：`<片名>_fov<N>_x<x>_y<y>_<µm每像素>umpp.tif`
- 清单：`fov_manifest.csv`（片名、序号、坐标、框大小、视野 µm、downsample、µm/px、文件名）
  - 按片子去重：同一张片子重导会替换旧记录，其它片子的行保留；表头不一致时整体重建

## 验收

1. 控制台框数量 = 你摆的框数量
2. 所有输出尺寸一致，且等于 `round(BOX_PX / DOWNSAMPLE)`
3. 打开一张确认位置正确、不是整张切片
4. 清单行数 = 图片数，坐标与图上一致

## 不做什么

- **不加比例尺**。QuPath 的比例尺（`View ▸ Show scalebar`）只是显示元素，不会写进导出文件；
  带比例尺的导出只有 `Export snapshot`（复制到剪贴板），尺寸受窗口限制。
  正确做法是导出纯图 + 在排版软件里加，比例尺是独立图层，整组图的样式才能统一。
- **不替你选位置**。取哪些视野是实验设计问题：大小必须一致、沿组织长轴分散、
  避开刀痕褶皱气泡大血管、全组用同一套规则、挑的时候最好不知道分组。
