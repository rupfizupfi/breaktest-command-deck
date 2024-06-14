import {ViewConfig} from '@vaadin/hilla-file-router/types.js';
import {ProjectService, SampleService} from "Frontend/generated/endpoints";
import {useParams} from "react-router-dom";
import {useEffect, useState} from "react";
import Project from "Frontend/generated/ch/rupfizupfi/deck/data/Project";
import Matcher from "Frontend/generated/com/vaadin/hilla/crud/filter/PropertyStringFilter/Matcher";
import {constraintServiceToFilter} from "Frontend/util/service";
import {VerticalLayout} from "@vaadin/react-components";
import Placeholder from "Frontend/components/placeholder/Placeholder";
import {buildAutoCrud} from "Frontend/components/autocrud/sample";

export const config: ViewConfig = {menu: {order: 2, icon: 'line-awesome/svg/file.svg', exclude: true}, title: 'Sample', loginRequired: true};

export default function SampleView() {
    const {projectId} = useParams();
    const [project, setProject] = useState<Project>();
    const [LocalSampleService, setLocalSampleService] = useState(SampleService);

    useEffect(() => {
        if (projectId) {
            ProjectService.get(parseFloat(projectId)).then(setProject);
            setLocalSampleService(constraintServiceToFilter(SampleService, {
                propertyId: 'project.id',
                filterValue: projectId,
                matcher: Matcher.EQUALS,
                '@type': 'propertyNumber',
            }));
        }
    }, [projectId]);

    if (!LocalSampleService) {
        return (<Placeholder/>);
    }

    if (!project) {
        return (<div className="flex flex-col h-full items-center justify-center p-l text-center box-border">
            <img style={{width: '200px'}} src="images/empty-plant.png"/>
            <h2>Project with id "{projectId}" doesn't exist</h2>
        </div>)
    }

    return (
        <VerticalLayout className='h-full'>
            <div className='flex-none'>
                <h3>{project.name}</h3>
            </div>
            <div className='flex-grow'>
                {buildAutoCrud(LocalSampleService)}
            </div>
        </VerticalLayout>
    );
}
