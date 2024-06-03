import type Pageable from "Frontend/generated/com/vaadin/hilla/mappedtypes/Pageable";
import type Filter from "Frontend/generated/com/vaadin/hilla/crud/filter/Filter";
import {EndpointRequestInit} from "@vaadin/hilla-frontend/Connect.js";

export interface ListEndpointService<T> {
    list(pageable: Pageable, filter: Filter | undefined, init?: EndpointRequestInit): Promise<T[]>;
}

export function constraintServiceToFilter<Item, T extends ListEndpointService<Item>>(endpointService: T, filter:Filter): T {
    return new Proxy(endpointService, {
        get: function(target, prop, receiver) {
            if (prop === 'list') {
                return (pageable: Pageable, filter: Filter | undefined, init?: EndpointRequestInit) => {
                    return target.list(pageable, {
                        "@type": "and",
                        "children": [filter, filter]
                    }, init);
                }
            }
            return Reflect.get(target, prop, receiver);
        }
    });
}