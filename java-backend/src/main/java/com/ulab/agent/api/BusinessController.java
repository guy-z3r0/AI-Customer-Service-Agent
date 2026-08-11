package com.ulab.agent.api;

import com.ulab.agent.api.dto.BusinessUpsertRequest;
import com.ulab.agent.api.dto.BusinessView;
import com.ulab.agent.api.dto.TransferDtos;
import com.ulab.agent.services.BusinessService;
import com.ulab.agent.services.BusinessTransferService;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** The businesses the agent can answer for, and which one is currently active. */
@RestController
@RequestMapping("/api/businesses")
public class BusinessController {

    private final BusinessService businesses;
    private final BusinessTransferService transfer;

    public BusinessController(BusinessService businesses, BusinessTransferService transfer) {
        this.businesses = businesses;
        this.transfer = transfer;
    }

    @GetMapping
    public List<BusinessView> list() {
        return businesses.list();
    }

    /** Returns 204 rather than 404 when nothing is active — that is a normal state. */
    @GetMapping("/active")
    public ResponseEntity<BusinessView> active() {
        BusinessView active = businesses.active();
        return active == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(active);
    }

    @GetMapping("/{id}")
    public BusinessView get(@PathVariable UUID id) {
        return businesses.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BusinessView create(@Valid @RequestBody BusinessUpsertRequest request) {
        return businesses.create(request);
    }

    @PutMapping("/{id}")
    public BusinessView update(@PathVariable UUID id, @Valid @RequestBody BusinessUpsertRequest request) {
        return businesses.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        businesses.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/activate")
    public BusinessView activate(@PathVariable UUID id) {
        return businesses.activate(id);
    }

    // ------------------------------------------------------ the whole business --

    /**
     * The business as a file: everything the editor's six tabs hold.
     *
     * An attachment rather than a page, so the browser saves it under a name
     * that says which business it is — the same arrangement the call transcript
     * download uses, and for the same reason.
     */
    @GetMapping("/{id}/export")
    public ResponseEntity<TransferDtos.BusinessDocument> export(@PathVariable UUID id) {
        TransferDtos.BusinessDocument document = transfer.export(id);
        ContentDisposition attachment = ContentDisposition.attachment()
                .filename(BusinessTransferService.fileNameFor(document.slug())).build();

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header("Content-Disposition", attachment.toString())
                .body(document);
    }

    /** Reads one of those files back, as a new business or over an existing one. */
    @PostMapping("/import")
    public TransferDtos.ImportResultView importBusiness(
            @Valid @RequestBody TransferDtos.ImportRequest request) {
        return transfer.importDocument(request);
    }
}
