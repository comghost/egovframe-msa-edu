package org.egovframe.cloud.portalservice.utils;

import static org.egovframe.cloud.portalservice.utils.PortalUtils.getPhysicalFileName;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.List;

import javax.annotation.PostConstruct;

import org.egovframe.cloud.common.exception.BusinessException;
import org.egovframe.cloud.common.exception.BusinessMessageException;
import org.egovframe.cloud.common.exception.dto.ErrorCode;
import org.egovframe.cloud.common.util.MessageUtil;
import org.egovframe.cloud.portalservice.api.attachment.dto.AttachmentBase64RequestDto;
import org.egovframe.cloud.portalservice.api.attachment.dto.AttachmentImageResponseDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * org.egovframe.cloud.portalservice.utils.FileStorageUtils
 * <p>
 * 파일 유틸리티 class
 *
 * @author 표준프레임워크센터 shinmj
 * @version 1.0
 * @since 2021/07/13
 *
 * <pre>
 * << 개정이력(Modification Information) >>
 *
 *     수정일        수정자           수정내용
 *  ----------    --------    ---------------------------
 *  2021/07/13    shinmj  최초 생성
 *  2022/09/22    신용호        File Upload / Download 취약점 개선
 * </pre>
 */
@Slf4j
@Getter
@Component
public class FileStorageUtils implements StorageUtils {

    private final Path fileStorageLocation;
    private final Environment environment;
    private final MessageUtil messageUtil;
    
    @Value("${fileUpload.extensions}")
    private String whiteListFileUploadExtensions;

    public FileStorageUtils(Environment environment, MessageUtil messageUtil) {
        this.environment = environment;
        this.fileStorageLocation = Paths.get(environment.getProperty("file.directory")).toAbsolutePath().normalize();
        this.messageUtil = messageUtil;
    }

    @PostConstruct
    public void init() {
        try {
            String ftpEnabled = environment.getProperty("ftp.enabled");
            if (!StringUtils.hasLength(ftpEnabled) || !"true".equals(ftpEnabled)) {
                log.info("FileStorageUtils createDirectories! ftpEnabled: {}", ftpEnabled);
                Files.createDirectories(this.fileStorageLocation);
            }
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, "Could not create the directory where the uploaded files will be stored.");
        }
    }

    /**
     * 저장 경로
     *
     * @param basePath
     * @return
     */
    public Path getStorePath(String basePath) {

        try {
            Path path = fileStorageLocation.resolve(basePath);
            Files.createDirectories(path);
            return path;
        } catch (IOException ex) {
            log.error("Could not create file store directory.", ex);
            // 파일을 저장할 수 없습니다. 다시 시도해 주세요.
            throw new BusinessMessageException(messageUtil.getMessage("valid.file.not_saved_try_again"));
        }
    }


    public String getContentType(String filename) {
        Path filePath = this.fileStorageLocation.resolve(StringUtils.cleanPath("/" + filename));
        String mimeType = null;
        try {
            mimeType = Files.probeContentType(filePath);
        } catch (IOException ex) {
            log.error("Files.probeContentType", ex);
        }

        return mimeType == null ? URLConnection.guessContentTypeFromName(filePath.toString()) : mimeType;
    }

    /**
     * .temp 제거
     *
     * @param physicalFileName
     * @return
     */
    public String renameTemp(String physicalFileName) {
        String rename = physicalFileName.replace(".temp", "");

        //물리적 파일 처리
        Path path = getStorePath("");
        File file = path.resolve(physicalFileName).toFile();
        File renameFile = path.resolve(rename).toFile();
        try {
            file.renameTo(renameFile);
        } catch (NullPointerException ex) {
            // 파일을 찾을 수 없습니다.
            throw new BusinessMessageException(messageUtil.getMessage("valid.file.not_found"));
        }
        return rename;
    }

    /**
     * base64 encoding 된 파일 저장
     *
     * @param requestDto
     * @param basePath
     * @return
     */
    public String storeBase64File(AttachmentBase64RequestDto requestDto, String basePath) {
        String filename = getPhysicalFileName(requestDto.getOriginalName(), false);

        // 확장자 검증
        checkExtension(filename, whiteListFileUploadExtensions, messageUtil);
        
        try {
            Path path = getStorePath(basePath);
            File file = path.resolve(filename).toFile();

            Base64.Decoder decoder = Base64.getDecoder();
            byte[] decodeBytes = decoder.decode(requestDto.getFileBase64().getBytes());

            try (FileOutputStream outputStream = new FileOutputStream(file)) {
                outputStream.write(decodeBytes);
            }

            return filename;

        } catch (IOException ex) {
            log.error("Could not stored base 64 file.", ex);
            // 파일을 저장할 수 없습니다. 다시 시도해 주세요.
            throw new BusinessMessageException(messageUtil.getMessage("valid.file.not_saved_try_again"));
        }
    }

    /**
     * base64 encoding 된 파일 저장
     *
     * @param requestDto
     * @return
     */
    public String storeBase64File(AttachmentBase64RequestDto requestDto) {
        return storeBase64File(requestDto, "");
    }

    /**
     * MultipartFile -> 물리적 파일 저장
     *
     * @param file     MultipartFile
     * @param basePath 기본 root외 파일이 저장될 경로
     * @param isTemp   .temp 파일 생성 여부
     * @return
     */
    public String storeFile(MultipartFile file, String basePath, boolean isTemp) {
    	
        // 확장자 검증
        checkExtension(file.getOriginalFilename(), whiteListFileUploadExtensions, messageUtil);
    	
        String filename = getPhysicalFileName(file.getOriginalFilename(), isTemp);

        try {
            if (basePath.contains("..")) {
                log.error("basePath contains invalid path : " + basePath);
                // 파일명이 잘못되었습니다.
                throw new BusinessMessageException(messageUtil.getMessage("valid.file.invalid_path"));
            }
            if (filename.contains("..")) {
                log.error("Filename contains invalid path sequence : " + filename);
                // 파일명이 잘못되었습니다.
                throw new BusinessMessageException(messageUtil.getMessage("valid.file.invalid_name"));
            }
            
            Path path = getStorePath(basePath);
            Path target = path.resolve(filename);
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);

            return filename;
        } catch (IOException ex) {
            log.error("Could not stored file", ex);
            // 파일을 저장할 수 없습니다. 다시 시도해 주세요.
            throw new BusinessMessageException(messageUtil.getMessage("valid.file.not_saved_try_again"));
        }
    }

    @Override
    public void storeFiles(List<File> files, String basePath) {
    }
    
	@Override
	public void storeFilesInitMessage(List<File> files, String basePath) {
	}

    /**
     * .temp 파일 생성하여 MultipartFile 저장
     *
     * @param file
     * @return
     */
    public String storeFileTemp(MultipartFile file) {
        return storeFile(file, "", true);
    }

    /**
     * .temp 파일 생성하여 MultipartFile 저장
     *
     * @param file
     * @param basePath
     * @return
     */
    public String storeFileTemp(MultipartFile file, String basePath) {
        return storeFile(file, basePath, true);
    }

    /**
     * MultipartFile 저장.
     *
     * @param file
     * @return
     */
    public String storeFile(MultipartFile file) {
        return storeFile(file, "", false);
    }

    /**
     * MultipartFile 저장.
     *
     * @param file
     * @param basePath
     * @return
     */
    public String storeFile(MultipartFile file, String basePath) {
        return storeFile(file, basePath, false);
    }

    /**
     * download file
     *
     * @param filename
     * @return
     */
    public Resource downloadFile(String filename) {
        Path path = getStorePath("");
        if (filename.contains("..")) {
            log.error("Filename contains invalid path sequence : " + filename);
            // 파일명이 잘못되었습니다.
            throw new BusinessMessageException(messageUtil.getMessage("valid.file.invalid_name"));
        }
        
        try {
        	//log.info("===>>> request fullPath : "+path.toString());
        	//log.info("===>>> request fullPath : "+path.resolve(filename).toUri());
            Resource resource = new UrlResource(path.resolve(filename).toUri());

            if (resource.exists()) {
                return resource;
            } else {
                log.error("Could not found resource");
                // 파일을 찾을 수 없습니다.
                throw new BusinessMessageException(messageUtil.getMessage("valid.file.not_found"));
            }
        } catch (MalformedURLException ex) {
            log.error("Could not found file.", ex);
            // 파일을 찾을 수 없습니다.
            throw new BusinessMessageException(messageUtil.getMessage("valid.file.not_found"));
        }
    }

    /**
     * image 태그에서 호출 시 byte 배열로 return
     *
     * @param imagename
     * @return public String getContentType(String filename) {
     * @throws IOException
     */
    public AttachmentImageResponseDto loadImage(String imagename) {
        try {
            Path imagePath = this.fileStorageLocation.resolve(imagename).normalize();
            try (InputStream is = new FileInputStream(imagePath.toFile())) {
                try (ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                    int read;
                    byte[] data = new byte[(int) imagePath.toFile().length()];
                    while ((read = is.read(data, 0, data.length)) != -1) {
                        buffer.write(data, 0, read);
                    }

                    return AttachmentImageResponseDto.builder()
                            .mimeType(getContentType(imagename))
                            .data(data)
                            .build();
                }
            }

        } catch (FileNotFoundException | NoSuchFileException ex) {
            // 파일을 찾을 수 없습니다.
            throw new BusinessMessageException(messageUtil.getMessage("valid.file.not_found"));
        } catch (IOException iex) {
            log.error("Could not read file.", iex);
            // 파일을 찾을 수 없습니다.
            throw new BusinessMessageException(messageUtil.getMessage("valid.file.not_found"));
        }
    }

    /**
     * 물리적 파일 삭제
     *
     * @param filename
     * @return
     */
    public boolean deleteFile(String filename) {
        Path path = getStorePath("");
        try {
            return Files.deleteIfExists(path.resolve(filename));
        } catch (IOException e) {
            log.error("Could not deleted file.", e);
            return false;
        }
    }

}
