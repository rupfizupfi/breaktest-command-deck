import {ListEndpointService} from "Frontend/util/service";
import type Pageable from "Frontend/generated/com/vaadin/hilla/mappedtypes/Pageable";

export type AutoComboService<T> = (value: String | null) => Promise<T[]>

export function createAutoComboBoxService<T>(endpointService: ListEndpointService<T>, searchField: String): AutoComboService<T> {
    return (value: String | null): Promise<T[]> => {
        const filter = value ? {
            "@type": "and",
            "children": [{"propertyId": searchField, "filterValue": value, "matcher": "CONTAINS", "@type": "propertyString", "key": searchField}]
        } : undefined;

        return endpointService.list({pageSize: 100} as Pageable, filter, undefined);
    }
}