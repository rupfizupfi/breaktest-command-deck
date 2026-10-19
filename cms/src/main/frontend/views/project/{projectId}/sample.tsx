import {ViewConfig} from '@vaadin/hilla-file-router/types.js';
import {ProjectService, SampleService} from "Frontend/generated/endpoints";
import {useParams} from "react-router";
import {useEffect, useState} from "react";
import Project from "Frontend/generated/ch/rupfizupfi/deck/data/Project";
import Matcher from "Frontend/generated/com/vaadin/hilla/crud/filter/PropertyStringFilter/Matcher";
import {constraintServiceToFilter} from "cms/util/service";
import {VerticalLayout} from "@vaadin/react-components";
import Placeholder from "cms/components/placeholder/Placeholder";
import {buildAutoCrud} from "cms/components/autocrud/sample";
import {Load, parseRouteId, settleLoad} from "cms/util/load";

export const config: ViewConfig = {menu: {order: 2, icon: 'line-awesome/svg/file.svg', exclude: true}, title: 'Sample', loginRequired: true};

export default function SampleView() {
    const {projectId} = useParams();
    const [load, setLoad] = useState<Load<Project>>({kind: 'loading'});
    const [LocalSampleService, setLocalSampleService] = useState(SampleService);

    useEffect(() => {
        const id = parseRouteId(projectId);
        if (id === undefined) {
            setLoad({kind: 'not-found'});
            return;
        }

        // A slow answer for the previous id must not replace the current one.
        let cancelled = false;
        setLoad({kind: 'loading'});
        settleLoad(ProjectService.get(id)).then((next) => {
            if (!cancelled) {
                setLoad(next);
            }
        });
        setLocalSampleService(constraintServiceToFilter(SampleService, {
            propertyId: 'project.id',
            filterValue: String(id),
            matcher: Matcher.EQUALS,
            '@type': 'propertyString',
        }));
        return () => {
            cancelled = true;
        };
    }, [projectId]);

    switch (load.kind) {
        case 'loading':
            return <Placeholder/>;
        case 'not-found':
        case 'error':
            return (
                <div className="flex flex-col h-full items-center justify-center p-l text-center box-border">
                    {/* Decorative: the heading below says what happened. */}
                    <img style={{width: '200px'}} src="images/empty-plant.png" alt=""/>
                    <h2>{load.kind === 'not-found'
                        ? `Project with id "${projectId}" doesn't exist`
                        : `Project "${projectId}" could not be loaded`}</h2>
                </div>
            );
        case 'found':
            return (
                <VerticalLayout className='h-full'>
                    <div className='flex-none'>
                        <h3>{load.value.name}</h3>
                    </div>
                    <div className='flex-grow'>
                        {buildAutoCrud(LocalSampleService)}
                    </div>
                </VerticalLayout>
            );
    }
}
