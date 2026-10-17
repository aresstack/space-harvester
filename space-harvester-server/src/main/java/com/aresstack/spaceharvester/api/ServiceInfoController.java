package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.ServiceInfo;
import com.aresstack.spaceharvester.application.ServiceInfoQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = ApiPaths.V1, produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Service", description = "Selbstauskunft des Space Harvester")
public class ServiceInfoController {

    private final ServiceInfoQuery serviceInfo;

    public ServiceInfoController(ServiceInfoQuery serviceInfo) {
        this.serviceInfo = serviceInfo;
    }

    @GetMapping("/info")
    @Operation(summary = "Name, Version und API-Version des Dienstes")
    public ServiceInfo info() {
        return serviceInfo.get();
    }
}
