<#if executionData.job.group??>
    <#assign jobName="${executionData.job.group} / ${executionData.job.name}">
<#else>
    <#assign jobName="${executionData.job.name}">
</#if>
{
    "username": "${username}",
<#if icon_url??>
    "icon_url": "${icon_url}",
</#if>
    "text": "[Execution](${executionData.href}) ${trigger} for job [${jobName}](${executionData.job.href})
:white_check_mark: **Success:** <#if executionData.succeededNodeList??><#list executionData.succeededNodeList as node>#${node} </#list><#else>None</#if>
:red_circle: **Failed:** <#if executionData.failedNodeList??><#list executionData.failedNodeList as node>#${node} </#list><#else>None</#if>
"
}
