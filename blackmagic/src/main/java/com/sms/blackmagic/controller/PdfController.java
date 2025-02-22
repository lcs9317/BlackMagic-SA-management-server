package com.sms.blackmagic.controller;

import com.sms.blackmagic.model.*;
import com.sms.blackmagic.model.Record;
import com.sms.blackmagic.model.User;
import com.sms.blackmagic.service.*;
import com.sms.blackmagic.util.AuditLogUtil;
import com.sms.blackmagic.util.JwtUtil;
import com.sms.blackmagic.util.PdfUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

@RestController
@RequestMapping("/pdf")
@RequiredArgsConstructor
public class PdfController {

    private final RecordService recordService;
    private final CompanyService companyService;
    private final AttachedFileService attachedFileService;

    @Autowired
    private AuditLogUtil auditLogUtil;

    @Autowired
    private JwtUtil jwtUtil;

    @PostMapping
    public ResponseEntity<String> uploadPdf(@RequestParam("file") MultipartFile file, HttpServletRequest request) throws IOException {
        if (file.isEmpty() || !file.getContentType().equalsIgnoreCase("application/pdf")) {
            return ResponseEntity.badRequest().body("PDF file is required");
        }

        File pdfFile = PdfUtils.convertMultipartFileToFile(file);
        Record record = PdfUtils.parsePdf(pdfFile);
        Company company = companyService.findByCompanyName(record.getCompany().getCompanyName());
        record.setCompany(company);

        Record getRecord = recordService.createRecord(record);
        PdfUtils.savePdf(file, getRecord.getRecordId().toString() + ".pdf");

        AttachedFile attachedFile = new AttachedFile();
        attachedFile.setUploadState(true);
        attachedFile.setRecord(getRecord);
        attachedFileService.createFile(attachedFile);

        // 감사 로그
        String token = jwtUtil.extractTokenFromRequest(request);
        auditLogUtil.saveAuditLog(token, getRecord, 1);

        return ResponseEntity.ok("success");
    }

    @GetMapping("/{fileId}")
    public ResponseEntity<Resource> downloadPdf(@PathVariable String fileId, Authentication authentication) throws IOException {

        // 인가를 위한 JWT 내 유저 정보
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();

        // 레코드 가져오기
        Record record = recordService.readRecord(Long.parseLong(fileId));

        // JWT 내 company_id와 다운로드 할 record의 company_id가 다르면 에러 발생
        if (userDetails.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_MASTER")) &&
                !((User)userDetails).getCompanyId().equals(record.getCompany().getCompanyId())) {
            System.out.println("authorization failed");
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }

        // 파일 경로
        String filePath = "src/main/resources/pdf/" + fileId + ".pdf";

        // 파일 리소스 생성
        Resource resource = new FileSystemResource(filePath);

        // 파일이 존재하는지 확인
        if (resource.exists()) {
            String mimeType = Files.probeContentType(new File(filePath).toPath());

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=" + fileId)
                    .contentType(MediaType.parseMediaType(mimeType))
                    .body(resource);
        } else {
            throw new RuntimeException("File not found");
        }
    }




}
