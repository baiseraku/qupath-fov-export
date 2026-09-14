/**
 * 批量导出固定尺寸视野（FOV）
 *
 * 用法
 *   GUI : Automate ▸ Show script editor → 打开本脚本 → 改下面参数 → Run（对当前图像生效）
 *   CLI : QuPath script -p 项目.qpproj -s export_fovs.groovy            // 项目里每张图各跑一次
 *         QuPath script -p 项目.qpproj -s export_fovs.groovy --args "[2.0, 4000, /输出目录]"
 *
 * 逻辑
 *   1. 找出图像里符合尺寸要求的标注框（默认 4000x4000 px 的正方形）
 *   2. 按从上到下、从左到右排序，依次导出为 PNG
 *   3. 文件名带片名、序号、坐标、最终 um/px
 */

import qupath.lib.regions.RegionRequest
import qupath.lib.images.writers.ImageWriterTools
import javax.imageio.ImageIO
import javax.imageio.IIOImage
import javax.imageio.ImageWriteParam

// ===== 参数（GUI 里改这里）=====
double DOWNSAMPLE = 2.0        // 2 → 0.207 um/px（≈48x）; 2.417 → 0.25 um/px（=40x）
List EXTRA_DOWNSAMPLES = []    // 同一个框额外再导的倍数，比如 [4.8345] 就同时出一套 20x（0.5 um/px）
double BOX_PX     = 4000       // 只导这个边长的正方形框；填 0 = 导所有非整图标注
String OUT_DIR    = new File(System.getProperty('user.home'), 'Downloads/QuPath_FOV').getAbsolutePath()
String EXT        = 'tif'      // 输出格式：tif / png / jpg
String TIFF_COMPRESSION = 'LZW'  // 只对 tif 生效：LZW（无损，推荐）/ Deflate（无损更小但慢）/ None（不压缩）
boolean WRITE_MANIFEST = true  // 顺便写一份 CSV 清单

// ===== 命令行覆盖 --args "[downsample, box_px, 输出目录, 额外倍数(可选,分号隔开)]" =====
def argv = null
try { argv = getBinding().getVariable('args') } catch (Exception ignored) { }
if (argv != null && argv.size() >= 3) {
    // 注意：--args "[2.0, 4000, /path]" 里逗号后面的空格会被保留，必须 trim
    DOWNSAMPLE = (argv[0] as String).trim().toDouble()
    BOX_PX     = (argv[1] as String).trim().toDouble()
    OUT_DIR    = (argv[2] as String).trim()
}
if (argv != null && argv.size() >= 4) {
    EXTRA_DOWNSAMPLES = (argv[3] as String).split(';')
            .collect { it.trim() }.findAll { it }.collect { it as double }
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
// 所有要导的倍率：主倍数 + 额外倍数，去重
List<Double> scales = ([DOWNSAMPLE] + EXTRA_DOWNSAMPLES.collect { (it as Number).doubleValue() }).unique()

def outDir = new File(OUT_DIR)
if (!outDir.exists() && !outDir.mkdirs()) {
    println "!! 无法创建输出目录: ${outDir}"
    return
}

// ---- 挑出要导的框 ----
def all = getAnnotationObjects()
def matches = { a ->
    def r = a.getROI()
    if (BOX_PX > 0) {
        Math.abs(r.getBoundsWidth() - BOX_PX) <= 1.5 && Math.abs(r.getBoundsHeight() - BOX_PX) <= 1.5
    } else {
        !(r.getBoundsWidth() >= server.getWidth() * 0.99 && r.getBoundsHeight() >= server.getHeight() * 0.99)
    }
}
def targets = all.findAll(matches)
def skipped = all.findAll { !matches(it) }

// 从上到下、从左到右，编号稳定
targets = targets.sort { a ->
    def r = a.getROI()
    Math.round(r.getBoundsY()) * 1.0e7 + Math.round(r.getBoundsX())
}

// 尺寸不符的框一定要报出来，否则画错一个就会静默少导一张
if (!skipped.isEmpty()) {
    println String.format('== %s: 跳过 %d 个尺寸不符的标注（目标边长 %.0f px）', slide, skipped.size(), BOX_PX)
    skipped.each { a ->
        def r = a.getROI()
        println String.format('   x %-20s %.0f x %.0f px', a.getName() ?: '(未命名)', r.getBoundsWidth(), r.getBoundsHeight())
    }
}

if (targets.isEmpty()) {
    println "== ${slide}: 没有符合条件的框"
    return
}

// ---- 导出 ----
println "== ${slide}  mpp=${String.format('%.4f', mpp)}  框 ${String.format('%.0f', BOX_PX)} px (视野 ${String.format('%.0f', BOX_PX * mpp)} um)"
scales.each { ds ->
    println String.format('   倍数 ds=%-7s -> %.1fx  %.4f um/px  输出 %d px', ds, 10.0 / (mpp * ds), mpp * ds, (int) Math.round(BOX_PX / ds))
}
println "   格式 ${EXT}${EXT.toLowerCase().startsWith('tif') ? " (${TIFF_COMPRESSION})" : ''}，找到 ${targets.size()} 个框，输出到 ${outDir}"

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
targets.eachWithIndex { a, i ->
    def roi = a.getROI()
    int x = (int) Math.round(roi.getBoundsX())
    int y = (int) Math.round(roi.getBoundsY())
    scales.each { ds ->
        double upp = mpp * ds
        String fname = String.format('%s_fov%d_x%d_y%d_%.3fumpp.%s', slide, i + 1, x, y, upp, EXT)
        def f = new File(outDir, fname)
        long t1 = System.currentTimeMillis()
        def request = RegionRequest.createInstance(server.getPath(), ds, roi)
        writeRegion(server, request, f)
        def img = ImageIO.read(f)
        println String.format('   fov%d  ds=%-7s %dx%d px (视野 %.0f um) -> %dx%d px  %.3f um/px  %.2f MB  %d ms',
                i + 1, ds, (int) roi.getBoundsWidth(), (int) roi.getBoundsHeight(), roi.getBoundsWidth() * mpp,
                img.getWidth(), img.getHeight(), upp, f.length() / 1048576.0, System.currentTimeMillis() - t1)
        rows << [slide, i + 1, x, y, (int) roi.getBoundsWidth(), (int) roi.getBoundsHeight(),
                 String.format('%.1f', roi.getBoundsWidth() * mpp), ds,
                 String.format('%.4f', upp), fname]
    }
}
println String.format('   合计 %d 张（%d 个框 × %d 个倍数），用时 %.1f 秒',
        rows.size(), targets.size(), scales.size(), (System.currentTimeMillis() - t0) / 1000.0)

// ---- 清单 ----
if (WRITE_MANIFEST) {
    def csv = new File(outDir, 'fov_manifest.csv')
    String header = 'slide,fov,x,y,box_px_x,box_px_y,field_um,downsample,um_per_px,file'
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
