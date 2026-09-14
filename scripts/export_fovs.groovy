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
double BOX_PX     = 4000       // 只导这个边长的正方形框；填 0 = 导所有非整图标注
String OUT_DIR    = new File(System.getProperty('user.home'), 'Downloads/QuPath_FOV').getAbsolutePath()
String EXT        = 'tif'      // 输出格式：tif / png / jpg
String TIFF_COMPRESSION = 'LZW'  // 只对 tif 生效：LZW（无损，推荐）/ Deflate（无损更小但慢）/ None（不压缩）
boolean WRITE_MANIFEST = true  // 顺便写一份 CSV 清单

// ===== 命令行覆盖 --args "[downsample, box_px, 输出目录]" =====
def argv = null
try { argv = getBinding().getVariable('args') } catch (Exception ignored) { }
if (argv != null && argv.size() >= 3) {
    // 注意：--args "[2.0, 4000, /path]" 里逗号后面的空格会被保留，必须 trim
    DOWNSAMPLE = (argv[0] as String).trim().toDouble()
    BOX_PX     = (argv[1] as String).trim().toDouble()
    OUT_DIR    = (argv[2] as String).trim()
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
double umPerPx = mpp * DOWNSAMPLE

def outDir = new File(OUT_DIR)
if (!outDir.exists() && !outDir.mkdirs()) {
    println "!! 无法创建输出目录: ${outDir}"
    return
}

// ---- 挑出要导的框 ----
def all = getAnnotationObjects()
def targets = (BOX_PX > 0)
        ? all.findAll { a ->
            def r = a.getROI()
            Math.abs(r.getBoundsWidth() - BOX_PX) <= 1.5 && Math.abs(r.getBoundsHeight() - BOX_PX) <= 1.5
        }
        : all.findAll { a ->
            def r = a.getROI()
            !(r.getBoundsWidth() >= server.getWidth() * 0.99 && r.getBoundsHeight() >= server.getHeight() * 0.99)
        }

// 从上到下、从左到右，编号稳定
targets = targets.sort { a ->
    def r = a.getROI()
    Math.round(r.getBoundsY()) * 1.0e7 + Math.round(r.getBoundsX())
}

if (targets.isEmpty()) {
    println "== ${slide}: 没有符合条件的框"
    if (BOX_PX > 0 && !all.isEmpty()) {
        println "   图像里现有标注的尺寸："
        all.each { a ->
            def r = a.getROI()
            println String.format('   - %-16s %.0f x %.0f px', a.getName() ?: '(未命名)', r.getBoundsWidth(), r.getBoundsHeight())
        }
    }
    return
}

// ---- 导出 ----
println "== ${slide}  mpp=${String.format('%.4f', mpp)}  downsample=${DOWNSAMPLE}  -> ${String.format('%.4f', umPerPx)} um/px"
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
    String fname = String.format('%s_fov%d_x%d_y%d_%.3fumpp.%s', slide, i + 1, x, y, umPerPx, EXT)
    def f = new File(outDir, fname)
    long t1 = System.currentTimeMillis()
    def request = RegionRequest.createInstance(server.getPath(), DOWNSAMPLE, roi)
    writeRegion(server, request, f)
    def img = ImageIO.read(f)
    println String.format('   fov%d  %dx%d px (视野 %.0f um) -> %dx%d px  %.2f MB  %d ms',
            i + 1, (int) roi.getBoundsWidth(), (int) roi.getBoundsHeight(), roi.getBoundsWidth() * mpp,
            img.getWidth(), img.getHeight(), f.length() / 1048576.0, System.currentTimeMillis() - t1)
    rows << [slide, i + 1, x, y, (int) roi.getBoundsWidth(), (int) roi.getBoundsHeight(),
             String.format('%.1f', roi.getBoundsWidth() * mpp), DOWNSAMPLE,
             String.format('%.4f', umPerPx), fname]
}
println String.format('   合计 %d 张，用时 %.1f 秒', rows.size(), (System.currentTimeMillis() - t0) / 1000.0)

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
