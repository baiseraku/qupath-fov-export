/**
 * 批量导出固定输出尺寸的视野（FOV）
 *
 * 用法
 *   GUI : Automate ▸ Show script editor → 打开本脚本 → 改下面参数 → Run（对当前图像生效）
 *   CLI : QuPath script -p 项目.qpproj -s export_fovs.groovy            // 项目里每张图各跑一次
 *         QuPath script -p 项目.qpproj -s export_fovs.groovy --args "[框边长, 输出边长, 输出目录]"
 *         QuPath script -p 项目.qpproj -s export_fovs.groovy --args "[4000;8000, 2000, /输出目录]"
 *
 * 逻辑
 *   1. 只挑边长等于 BOX_SIZES 里某个值的框（能同时处理多个尺寸）
 *   2. 导出倍率自动算：downsample = 框边长 / OUT_PX —— 所以多大框出来的图都是 OUT_PX 见方
 *   3. 按框尺寸分组，组内从上到下、从左到右编号
 *   4. 文件名带片名、框尺寸、序号、坐标、最终 um/px；另写一份 CSV 清单
 */

import qupath.lib.regions.RegionRequest
import qupath.lib.images.writers.ImageWriterTools
import javax.imageio.ImageIO
import javax.imageio.IIOImage
import javax.imageio.ImageWriteParam

// ===== 参数（GUI 里改这里）=====
// 导出倍率不用手填：downsample = 框边长 / OUT_PX，多大框的图都是同一个像素尺寸
List<Double> BOX_SIZES = [4000, 8000]  // 允许的框边长（px），可写多个；填 [] = 导所有非整图标注
double OUT_PX   = 2000         // 目标输出边长（px）。4000 的框→ds 2，8000 的框→ds 4
List<Double> EXTRA_OUT_PX = [] // 同一个框额外再导的输出尺寸，比如 [1000] 就再多一套小图
String OUT_DIR  = new File(System.getProperty('user.home'), 'Downloads/QuPath_FOV').getAbsolutePath()
String EXT      = 'tif'        // 输出格式：tif / png / jpg
String TIFF_COMPRESSION = 'LZW'  // 只对 tif 生效：LZW（无损，推荐）/ Deflate（无损更小但慢）/ None（不压缩）
boolean WRITE_MANIFEST = true  // 顺便写一份 CSV 清单

// ===== 命令行覆盖 --args "[框边长(分号隔开), 输出边长, 输出目录, 额外输出尺寸(可选,分号隔开)]" =====
def argv = null
try { argv = getBinding().getVariable('args') } catch (Exception ignored) { }
def parseNums = { s ->
    (s as String).split(';').collect { it.trim() }.findAll { it }.collect { it as double }
}
if (argv != null && argv.size() >= 3) {
    // 注意：--args "[4000;8000, 2000, /path]" 里逗号后面的空格会被保留，必须 trim
    BOX_SIZES = parseNums(argv[0])
    OUT_PX    = (argv[1] as String).trim().toDouble()
    OUT_DIR   = (argv[2] as String).trim()
}
if (argv != null && argv.size() >= 4) {
    EXTRA_OUT_PX = parseNums(argv[3])
}
if (!OUT_DIR.startsWith('/')) {
    println "!! 输出目录必须是绝对路径，当前是：'${OUT_DIR}'"
    return
}

def entry = getProjectEntry()
def imageData = getCurrentImageData()
def server = imageData.getServer()
String slide = entry != null ? entry.getImageName() : (server.getMetadata().getName() ?: 'image')
slide = slide.replaceFirst(/ - Image\d+$/, '').replaceFirst(/(\.ome)?\.tiff?$/, '')
slide = slide.replaceAll(/[^A-Za-z0-9._-]/, '_')

double mpp = server.getPixelCalibration().getPixelWidthMicrons()
// 每个框要导的输出尺寸（px），去重
List<Double> outSizes = ([OUT_PX] + EXTRA_OUT_PX.collect { (it as Number).doubleValue() }).unique()

def outDir = new File(OUT_DIR)
if (!outDir.exists() && !outDir.mkdirs()) {
    println "!! 无法创建输出目录: ${outDir}"
    return
}

// ---- 挑出要导的框 ----
// 把实际边长归到最近的允许尺寸，作为分组标签（容差内也归得对）
def sizeLabel = { double w ->
    if (BOX_SIZES.isEmpty()) return String.format('%.0fpx', w)
    double best = BOX_SIZES.min { Math.abs(it - w) }
    return String.format('%.0fpx', best)
}
def matches = { a ->
    def r = a.getROI()
    if (BOX_SIZES.isEmpty()) {
        !(r.getBoundsWidth() >= server.getWidth() * 0.99 && r.getBoundsHeight() >= server.getHeight() * 0.99)
    } else {
        BOX_SIZES.any { s -> Math.abs(r.getBoundsWidth() - s) <= 1.5 && Math.abs(r.getBoundsHeight() - s) <= 1.5 }
    }
}
def all = getAnnotationObjects()
def targets = all.findAll(matches)
def skipped = all.findAll { !matches(it) }

// 尺寸不符的框一定要报出来，否则画错一个就会静默少导一张
if (!skipped.isEmpty()) {
    println String.format('== %s: 跳过 %d 个尺寸不符的标注（允许的边长：%s）', slide, skipped.size(),
            BOX_SIZES.isEmpty() ? '不限（只排除整图标注）' : BOX_SIZES.collect { String.format('%.0f', it) }.join(' / '))
    skipped.each { a ->
        def r = a.getROI()
        println String.format('   x %-20s %.0f x %.0f px', a.getName() ?: '(未命名)', r.getBoundsWidth(), r.getBoundsHeight())
    }
}

if (targets.isEmpty()) {
    println "== ${slide}: 没有符合条件的框"
    return
}

// 按框尺寸分组，组内从上到下、从左到右编号
// （编号在组内独立，这样 fov1 在不同片子间才是同一个位置）
def groups = targets.groupBy { a -> sizeLabel(a.getROI().getBoundsWidth()) }
def groupKeys = groups.keySet().sort { it }

// ---- 导出 ----
println "== ${slide}  mpp=${String.format('%.4f', mpp)}  输出统一 ${String.format('%.0f', OUT_PX)} px  格式 ${EXT}${EXT.toLowerCase().startsWith('tif') ? " (${TIFF_COMPRESSION})" : ''}"
groupKeys.each { g ->
    double box = groups[g][0].getROI().getBoundsWidth()
    double ds = Math.max(1.0, box / OUT_PX)
    println String.format('   %-8s  %d 个框  视野 %.0f um  ds=%.4f  ->  %.1fx  %.4f um/px',
            g, groups[g].size(), box * mpp, ds, 10.0 / (mpp * ds), mpp * ds)
}
println "   输出目录 ${outDir}"

// 写文件：tif 自己控压缩，其它交给 QuPath 默认 writer
def writeRegion = { srv, request, File f ->
    if (EXT.toLowerCase().startsWith('tif')) {
        def img = srv.readRegion(request)
        def w = ImageIO.getImageWritersByFormatName('TIFF').next()
        def ios = ImageIO.createImageOutputStream(f)
        try {
            w.setOutput(ios)
            def p = w.getDefaultWriteParam()
            if (TIFF_COMPRESSION != null && !TIFF_COMPRESSION.equalsIgnoreCase('none')) {
                p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT)
                p.setCompressionType(TIFF_COMPRESSION)
            }
            w.write(null, new IIOImage(img, null, null), p)
        } finally {
            w.dispose()
            ios.close()
        }
    } else {
        ImageWriterTools.writeImageRegion(srv, request, f.getAbsolutePath())
    }
}

long t0 = System.currentTimeMillis()
def rows = []
groupKeys.each { g ->
    groups[g].sort { a ->
        def r = a.getROI()
        Math.round(r.getBoundsY()) * 1.0e7 + Math.round(r.getBoundsX())
    }.eachWithIndex { a, i ->
        def roi = a.getROI()
        int x = (int) Math.round(roi.getBoundsX())
        int y = (int) Math.round(roi.getBoundsY())
        double box = roi.getBoundsWidth()
        int fovNo = i + 1
        outSizes.each { op ->
            double ds = Math.max(1.0, box / op)
            double upp = mpp * ds
            String fname = String.format('%s_%s_fov%d_x%d_y%d_%.3fumpp.%s', slide, g, fovNo, x, y, upp, EXT)
            def f = new File(outDir, fname)
            long t1 = System.currentTimeMillis()
            def request = RegionRequest.createInstance(server.getPath(), ds, roi)
            writeRegion(server, request, f)
            def img = ImageIO.read(f)
            println String.format('   %-8s fov%-3d ds=%-7.4f  视野 %4.0f um -> %dx%d px  %.3f um/px  %.2f MB  %d ms',
                    g, fovNo, ds, box * mpp, img.getWidth(), img.getHeight(), upp,
                    f.length() / 1048576.0, System.currentTimeMillis() - t1)
            rows << [slide, g, fovNo, x, y, (int) Math.round(box), (int) Math.round(op), ds,
                     String.format('%.4f', upp), String.format('%.1f', box * mpp), fname]
        }
    }
}
println String.format('   合计 %d 张（%d 个框 × %d 个输出尺寸），用时 %.1f 秒',
        rows.size(), targets.size(), outSizes.size(), (System.currentTimeMillis() - t0) / 1000.0)

// ---- 清单 ----
if (WRITE_MANIFEST) {
    def csv = new File(outDir, 'fov_manifest.csv')
    String header = 'slide,group,fov,x,y,box_px,out_px,downsample,um_per_px,field_um,file'
    def kept = []
    if (csv.exists()) {
        def lines = csv.readLines('UTF-8')
        if (!lines.isEmpty() && lines[0] == header) {
            // 保留其它片子的记录；同一张片子重新导出时，用新记录替换旧的，避免重复行
            kept = lines.tail().findAll { it.trim() && !it.startsWith(slide + ',') }
        }
    }
    csv.withWriter('UTF-8') { w ->
        w.println(header)
        kept.each { w.println(it) }
        rows.each { r -> w.println(r.join(',')) }
    }
    println "   清单: ${csv}" + (kept.isEmpty() ? '' : "（保留了其它片子 ${kept.size()} 行）")
}
