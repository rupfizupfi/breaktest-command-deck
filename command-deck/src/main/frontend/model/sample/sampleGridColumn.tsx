import {TextField} from "@vaadin/react-components";
import {HeaderFilterRendererProps} from "@vaadin/hilla-react-crud";
import Matcher from "Frontend/generated/com/vaadin/hilla/crud/filter/PropertyStringFilter/Matcher";


export default {
    renderer: ({item}: { item: any }) => item.sample?.name,

    headerFilterRenderer: ({setFilter}: HeaderFilterRendererProps) => (
        <TextField
            placeholder='Filter...'
            onValueChanged={({detail}) =>
                setFilter({
                    "@type": "or",
                    "children": [
                        {
                            propertyId: 'sample.name',
                            filterValue: detail.value,
                            matcher: Matcher.CONTAINS,
                            '@type': 'propertyString',
                        },
                        {
                            propertyId: 'sample.description',
                            filterValue: detail.value,
                            matcher: Matcher.CONTAINS,
                            '@type': 'propertyString',
                        }]
                })
            }
        />
    )
}