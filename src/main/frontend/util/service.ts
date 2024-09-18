import type Pageable from "Frontend/generated/com/vaadin/hilla/mappedtypes/Pageable";
import type Filter from "Frontend/generated/com/vaadin/hilla/crud/filter/Filter";
import {EndpointRequestInit} from "@vaadin/hilla-frontend/Connect.js";

export interface ListEndpointService<T> {
    list(pageable: Pageable, filter: Filter | undefined, init?: EndpointRequestInit): Promise<T[]>;
}

export function constraintServiceToFilter<Item, T extends ListEndpointService<Item>>(endpointService: T, mainFilter:Filter): T {
    return new Proxy(endpointService, {
        get: function(target, prop, receiver) {
            if (prop === 'list') {
                return (pageable: Pageable, filter: Filter | undefined, init?: EndpointRequestInit) => {
                    return target.list(pageable, {
                        "@type": "and",
                        "children": [mainFilter, filter]
                    }, init).then((items)=>items.map(replaceNullValues));
                }
            }
            if (prop === 'save') {
                return (entity: TestResult, init: EndpointRequestInit | undefined) => {
                    return target.save(entity, init).then((value)=>replaceNullValues(value))
                }
            }
            return Reflect.get(target, prop, receiver);
        }
    });
}

/**
 * The Vaadin hilla library has  some serious problems with null or not existing values
 */
function replaceNullValues(item:any) {
    if(!item.startRampSeconds) item.startRampSeconds = 0;
    if(!item.stopRampSeconds) item.stopRampSeconds = 0;
    return item;
}