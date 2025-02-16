import {TextField} from "@vaadin/react-components";
import Matcher from "Frontend/generated/com/vaadin/hilla/crud/filter/PropertyStringFilter/Matcher";
import React from "react";
import {HeaderFilterRendererProps} from "@vaadin/hilla-react-crud";

export default {
    renderer: function ({item}: { item: any }) {
        if (item.owner) {
            return item.owner.username + ' (' + item.owner.name + ')';
        }
        return 'all';
    },

    headerFilterRenderer: ({setFilter}:HeaderFilterRendererProps) => (
        <TextField
            placeholder='Filter...'
            onValueChanged={({detail}) =>
                setFilter({
                    "@type": "or",
                    "children": [
                        {
                            propertyId: 'owner.username',
                            filterValue: detail.value,
                            matcher: Matcher.CONTAINS,
                            '@type': 'propertyString',
                        },
                        {
                            propertyId: 'owner.name',
                            filterValue: detail.value,
                            matcher: Matcher.CONTAINS,
                            '@type': 'propertyString',
                        }]
                })
            }
        />
    )
}