package com.dataentry.service;

import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

 
@Service
public class OcrGate {

    private final Semaphore gate = new Semaphore(1, true);
    private final long waitSeconds;
    private final String tessdataPath;
    private final String languages;

    public OcrGate(
            @Value("${app.ocr.tessdata-path:/usr/share/tesseract-ocr/4.00/tessdata/}") String tessdataPath,
            @Value("${app.ocr.languages:ara+eng}") String languages,
            @Value("${app.ocr.wait-seconds:300}") long waitSeconds) {
        this.tessdataPath = tessdataPath;
        this.languages = languages;
        this.waitSeconds = waitSeconds;
    }

    public String ocrFile(File file) throws TesseractException {
        return run(t -> {
            try {
                return t.doOCR(file);
            } catch (TesseractException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public String ocrImage(BufferedImage image) throws TesseractException {
        return run(t -> {
            try {
                return t.doOCR(image);
            } catch (TesseractException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public String run(Function<ITesseract, String> fn) throws TesseractException {
        boolean acquired = false;
        try {
            acquired = gate.tryAcquire(waitSeconds, TimeUnit.SECONDS);
            if (!acquired) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "OCR engine is busy — try again in a moment");
            }
            Tesseract t = new Tesseract();
            t.setDatapath(tessdataPath);
            t.setLanguage(languages);
            try {
                return fn.apply(t);
            } catch (RuntimeException re) {
                if (re.getCause() instanceof TesseractException te) throw te;
                throw re;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OCR interrupted");
        } finally {
            if (acquired) gate.release();
        }
    }
}
