import type Pageable from "Frontend/generated/com/vaadin/hilla/mappedtypes/Pageable";
import type Filter from "Frontend/generated/com/vaadin/hilla/crud/filter/Filter";
import {EndpointRequestInit} from "@vaadin/hilla-frontend/Connect.js";

export interface ListEndpointService<T> {
    list(pageable: Pageable, filter: Filter | undefined, init?: EndpointRequestInit): Promise<T[]>;
}

export function constraintServiceToFilter<Item, T extends ListEndpointService<Item>>(endpointService: T, mainFilter:Filter): T {
    const filteredEndpointService = {...endpointService};
    filteredEndpointService.list = (pageable: Pageable, filter: Filter | undefined, init?: EndpointRequestInit) => {
        return endpointService.list(pageable, {
            "@type": "and",
            "children": [mainFilter, filter]
        }, init);
    }
    return filteredEndpointService;
}