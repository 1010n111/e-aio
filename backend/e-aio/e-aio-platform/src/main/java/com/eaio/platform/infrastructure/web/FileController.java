package com.eaio.platform.infrastructure.web;

import java.io.IOException;
import java.io.InputStream;

import com.eaio.common.api.PageResult;
import com.eaio.platform.api.dto.FileBindCmd;
import com.eaio.platform.api.dto.FileDTO;
import com.eaio.platform.api.dto.FileDelCmd;
import com.eaio.platform.api.dto.FileDownloadCmd;
import com.eaio.platform.api.dto.FileIdCmd;
import com.eaio.platform.api.dto.FileQuery;
import com.eaio.platform.api.dto.FileUploadCmd;
import com.eaio.platform.api.dto.FileUrlCmd;
import com.eaio.platform.api.dto.FileUrlDTO;
import com.eaio.platform.application.FileAppService;
import com.eaio.platform.application.file.FileDownloadResource;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件中心 REST 入口（P1 册 5.2 的 7 个端点：Upload/GetMeta/GetUrl/Download/Bind/Del/GetPage）。
 *
 * <p>三条契约（P0 册 3.9、{@code docs/agents/api-conventions.md}）：
 * <ul>
 *   <li>全部 POST + JSON，路径省略 context-path（{@code /api} 由 servlet context-path 承载）；</li>
 *   <li>控制器不自己拼返回体：出站由应用壳的 {@code ApiResponseAdvice} 统一包成 {@code Result<T>}；
 *       {@code Download} 是唯一例外——它返回二进制，应用壳对 {@code Resource} 有旁路（见该类注释）；</li>
 *   <li>权限点与设计册 7.3 <b>逐字一致</b>（{@code platform:file:*}）；iam 交付前这些注解是契约载体。</li>
 * </ul>
 *
 * <p><b>上传的幂等</b>：{@code /file/Upload} 在 5.6 的例外清单里（服务端自身幂等，3.3.3），入站幂等过滤器
 * 不要求 {@code Idempotency-Key}；{@code Bind}/{@code Del} 是普通写动作，缺键由过滤器直接 10001。
 *
 * <p><b>下载的两条路径</b>：
 * <ol>
 *   <li>{@code POST /platform/file/Download}：JSON {@code {fileId}}，走完整权限判定；</li>
 *   <li>{@code GET /platform/file/Download?fileId=&exp=&sig=}：预签名链接（{@code GetUrl} 签发的就是它）。
 *       校验顺序是**先比签名、再判时效**（3.3.8），随后**仍走同一套可见性判定**（3.3.4：token 不绕过数据权限）。
 *       GET 不经过幂等过滤器（只拦 POST），也不带任何写语义。</li>
 * </ol>
 */
@RestController
public class FileController {

    private final FileAppService service;

    public FileController(FileAppService service) {
        this.service = service;
    }

    // ---------------------------------------------------------------- 上传

    /**
     * 单文件上传（{@code multipart/form-data}）：{@code file} 是文件部分，{@code bizType}/{@code bizId} 可选
     * （同时给就在同一次请求里绑定，省掉一次 {@code Bind} 往返）。
     *
     * <p>大小声明用 multipart 的 {@code size}（框架在解析时就统计好了），流本身交给存储适配器边写边校验；
     * {@code -1}（未知）不会出现在这里——multipart 的大小一定可知。
     */
    @PostMapping("/platform/file/Upload")
    @PreAuthorize("hasAuthority('platform:file:upload')")
    public FileDTO upload(@RequestParam("file") MultipartFile file,
            @RequestParam(value = "bizType", required = false) String bizType,
            @RequestParam(value = "bizId", required = false) Long bizId) throws IOException {
        // 零字节文件的判据在服务层（实际字节数为 0 → 20010）：这里不预先判"空"，
        // 因为 multipart 的 isEmpty() 会把"零字节文件"与"没有文件部分"混成一个结果，分不清就报不准。
        try (InputStream in = file.getInputStream()) {
            FileUploadCmd cmd = new FileUploadCmd(file.getOriginalFilename(), file.getContentType(),
                    file.getSize(), in, bizType, bizId);
            return service.upload(cmd);
        }
    }

    // ---------------------------------------------------------------- 读

    /**
     * 元数据；文件不存在或已删除抛 20014，不可见抛 20017（5.2 给本端点列了 20014/20017）。
     *
     * <p>判定复用 {@link FileAppService#requireVisible(long)}：与下载同一条规则、同一处实现。
     * 元数据里有文件名、摘要与上传者，是实打实的信息泄漏面，不能"能看元数据但不能下载"。
     */
    @PostMapping("/platform/file/GetMeta")
    @PreAuthorize("hasAuthority('platform:file:get')")
    public FileDTO getMeta(@Valid @RequestBody FileIdCmd cmd) {
        service.requireVisible(cmd.fileId());
        return service.getMeta(cmd.fileId());
    }

    /** 带外下载链接（先判权限再签 token）；有效期由 {@code FilePresignTokenService} 夹取上限。 */
    @PostMapping("/platform/file/GetUrl")
    @PreAuthorize("hasAuthority('platform:file:get')")
    public FileUrlDTO getUrl(@Valid @RequestBody FileUrlCmd cmd) {
        service.requireVisible(cmd.fileId());
        return service.getUrl(cmd.fileId(), cmd.expireSeconds() == null ? 0 : cmd.expireSeconds());
    }

    /** 文件分页（管理页）。 */
    @PostMapping("/platform/file/GetPage")
    @PreAuthorize("hasAuthority('platform:file:list')")
    public PageResult<FileDTO> getPage(@RequestBody(required = false) FileQuery query) {
        return service.page(query);
    }

    // ---------------------------------------------------------------- 下载

    /** 下载（JSON 入参路径）：权限判定失败抛 20017，且**拒绝也要留痕**（3.3.6）。 */
    @PostMapping("/platform/file/Download")
    @PreAuthorize("hasAuthority('platform:file:download')")
    public ResponseEntity<Resource> download(@Valid @RequestBody FileIdCmd cmd) {
        return openDownload(cmd.fileId());
    }

    /**
     * 预签名下载（链接路径）：签名与时效 → 同一个文件 → 同一套可见性判定 → 同一套响应头。
     *
     * <p><b>刻意不加 {@code @PreAuthorize}</b>：5.2 给 {@code GetUrl} 的权限点是 {@code platform:file:get}，
     * 而 3.3.4 的可见性规则是"本人 / 同组织 / 权限点 {@code platform:file:download}"**三者任一**。
     * 若这里也要求 {@code :download}，那么"只有 {@code :get} 的非上传者、非同组织用户"会拿到一条
     * 签好名却必然 403 的链接（签名白签），"上传了文件但只有 {@code :get} 的调用方"也下不了自己的文件。
     * 安全性不降：下面的 {@code requireVisible} 就是那套三者任一判定，token 不能绕过它。
     */
    @GetMapping("/platform/file/Download")
    public ResponseEntity<Resource> downloadBySignature(@RequestParam("fileId") long fileId,
            @RequestParam(value = "exp", required = false) String exp,
            @RequestParam(value = "sig", required = false) String sig) {
        service.requireValidPresign(fileId, exp, sig);
        return openDownload(fileId);
    }

    // ---------------------------------------------------------------- 写

    /** 绑定业务对象（幂等键：是；重复绑定不报错）。 */
    @PostMapping("/platform/file/Bind")
    @PreAuthorize("hasAuthority('platform:file:bind')")
    public void bind(@Valid @RequestBody FileBindCmd cmd) {
        service.bind(cmd);
    }

    /**
     * 逻辑删除（幂等键：是；乐观锁 10003）。
     *
     * <p>{@code {fileId, version}} 的版本判定在应用服务里（一个事务内完成"查 + 比对 + 删"），
     * 不在控制器里做两次调用——理由见 {@code FileAppService.del(FileDelCmd)}。
     */
    @PostMapping("/platform/file/Del")
    @PreAuthorize("hasAuthority('platform:file:del')")
    public void del(@Valid @RequestBody FileDelCmd cmd) {
        service.requireVisible(cmd.fileId());
        service.del(cmd);
    }

    // ---------------------------------------------------------------- 内部

    /** 判定可见性 → 留痕 → 打开流 → 生成响应头（两条下载路径共用，避免两处响应头分叉）。 */
    private ResponseEntity<Resource> openDownload(long fileId) {
        service.requireVisible(fileId);
        FileDownloadCmd cmd = FileDownloadCmd.attachment(fileId, null);
        service.auditDownload(cmd);
        Resource resource = service.download(cmd);
        String contentType = resource instanceof FileDownloadResource
                ? service.getMeta(fileId).contentType()
                : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        HttpHeaders headers = FileDownloadHeaders.of(contentType, resource.getFilename());
        return ResponseEntity.ok().headers(headers).body(resource);
    }
}
