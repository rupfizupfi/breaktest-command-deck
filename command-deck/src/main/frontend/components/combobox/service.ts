import {ListEndpointService} from "Frontend/util/service";
import type Pageable from "Frontend/generated/com/vaadin/hilla/mappedtypes/Pageable";
import Filter from "Frontend/generated/com/vaadin/hilla/crud/filter/Filter";

export type AutoComboService<T> = (value: String | null) => Promise<T[]>

export function createAutoComboBoxService<T>(endpointService: ListEndpointService<T>, searchFields: String[] | String): AutoComboService<T> {
    return (value: String | null): Promise<T[]> => {
        const filter = value ? createFilters(searchFields, value) : undefined;

        return endpointService.list({pageSize: 100} as Pageable, filter, undefined);
    }
}

function createFilters(searchFields: String[] | String, value: String): Filter {
    if (Array.isArray(searchFields)) {
        return {
            "@type": "or",
            "children": searchFields.map(searchField => ({
                "propertyId": searchField,
                "filterValue": value,
                "matcher": "CONTAINS",
                "@type": "propertyString",
                "key": searchField
            }))
        }
    }
    return {
        "propertyId": searchFields,
        "filterValue": value,
        "matcher": "CONTAINS",
        "@type": "propertyString",
        "key": searchFields
    }
}