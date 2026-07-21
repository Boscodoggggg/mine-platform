#!/usr/bin/env ruby

require "yaml"
require "uri"

ROOT_DIR = File.expand_path("..", __dir__)
ISSUE_TEMPLATE_DIR = File.join(ROOT_DIR, ".github", "ISSUE_TEMPLATE")
ALLOWED_FORM_TYPES = %w[markdown input textarea dropdown checkboxes].freeze
ALLOWED_FORM_KEYS = %w[name description title labels assignees body].freeze

def fail_with(message)
  warn "FAIL: #{message}"
  exit 1
end

def load_yaml(relative_path)
  path = File.join(ROOT_DIR, relative_path)
  YAML.safe_load(File.read(path), permitted_classes: [], permitted_symbols: [], aliases: false)
rescue Psych::Exception => error
  fail_with("#{relative_path} 不是合法 YAML：#{error.message}")
end

def non_empty_string?(value)
  value.is_a?(String) && !value.strip.empty?
end

def validate_issue_form(file_name)
  relative_path = File.join(".github", "ISSUE_TEMPLATE", file_name)
  form = load_yaml(relative_path)
  fail_with("#{relative_path} 顶层必须是对象") unless form.is_a?(Hash)

  unknown_keys = form.keys.map(&:to_s) - ALLOWED_FORM_KEYS
  fail_with("#{relative_path} 包含未知顶层字段 #{unknown_keys.join(', ')}") unless unknown_keys.empty?
  %w[name description].each do |field|
    fail_with("#{relative_path} 缺少非空 #{field}") unless non_empty_string?(form[field])
  end
  fail_with("#{relative_path} 的 body 必须是非空数组") unless form["body"].is_a?(Array) && !form["body"].empty?
  fail_with("#{relative_path} 的 labels 必须是字符串数组") unless form.fetch("labels", []).is_a?(Array) && form.fetch("labels", []).all? { |label| non_empty_string?(label) }

  ids = []
  form["body"].each_with_index do |item, index|
    location = "#{relative_path} body[#{index}]"
    fail_with("#{location} 必须是对象") unless item.is_a?(Hash)
    type = item["type"]
    fail_with("#{location} 包含不支持的 type #{type.inspect}") unless ALLOWED_FORM_TYPES.include?(type)
    attributes = item["attributes"]
    fail_with("#{location} 缺少 attributes 对象") unless attributes.is_a?(Hash)

    if type == "markdown"
      fail_with("#{location} 缺少 markdown value") unless non_empty_string?(attributes["value"])
      next
    end

    id = item["id"]
    fail_with("#{location} 缺少合法 id") unless id.is_a?(String) && id.match?(/\A[a-zA-Z0-9_-]+\z/)
    fail_with("#{relative_path} 存在重复 id #{id}") if ids.include?(id)
    ids << id
    fail_with("#{location} 缺少非空 label") unless non_empty_string?(attributes["label"])

    if type == "dropdown"
      options = attributes["options"]
      fail_with("#{location} 的 options 必须是非空字符串数组") unless options.is_a?(Array) && !options.empty? && options.all? { |option| non_empty_string?(option) }
    elsif type == "checkboxes"
      options = attributes["options"]
      valid_options = options.is_a?(Array) && !options.empty? && options.all? do |option|
        option.is_a?(Hash) && non_empty_string?(option["label"]) && [true, false, nil].include?(option["required"])
      end
      fail_with("#{location} 的 options 必须包含合法 label 和布尔 required") unless valid_options
    end

    validations = item.fetch("validations", {})
    fail_with("#{location} 的 validations 必须是对象") unless validations.is_a?(Hash)
    required = validations["required"]
    fail_with("#{location} 的 validations.required 必须是布尔值") unless [true, false, nil].include?(required)
  end
end

def valid_https_url?(value)
  uri = URI.parse(value)
  uri.is_a?(URI::HTTPS) && !uri.host.to_s.empty?
rescue URI::InvalidURIError
  false
end

validate_issue_form("bug_report.yml")
validate_issue_form("feature_request.yml")

config_path = File.join(".github", "ISSUE_TEMPLATE", "config.yml")
config = load_yaml(config_path)
fail_with("#{config_path} 顶层必须是对象") unless config.is_a?(Hash)
fail_with("#{config_path} 必须关闭空白 Issue") unless config["blank_issues_enabled"] == false
links = config["contact_links"]
fail_with("#{config_path} 的 contact_links 必须是非空数组") unless links.is_a?(Array) && !links.empty?
links.each_with_index do |link, index|
  location = "#{config_path} contact_links[#{index}]"
  valid = link.is_a?(Hash) && non_empty_string?(link["name"]) && non_empty_string?(link["about"]) && valid_https_url?(link["url"])
  fail_with("#{location} 必须包含 name、HTTPS url 和 about") unless valid
end

security_url = "https://github.com/Boscodoggggg/mine-platform/security/advisories/new"
fail_with("#{config_path} 缺少私密漏洞报告入口") unless links.any? { |link| link["url"] == security_url }

codeowners_path = File.join(ROOT_DIR, ".github", "CODEOWNERS")
fail_with("单维护者阶段不应配置 CODEOWNERS 自动请求本人审查") if File.exist?(codeowners_path)

dependabot_path = File.join(".github", "dependabot.yml")
dependabot = load_yaml(dependabot_path)
updates = dependabot.is_a?(Hash) ? dependabot["updates"] : nil
fail_with("#{dependabot_path} 必须包含更新配置") unless updates.is_a?(Array) && !updates.empty?

expected_ecosystems = %w[maven npm docker-compose github-actions]
actual_ecosystems = updates.map { |update| update["package-ecosystem"] }.compact
missing_ecosystems = expected_ecosystems - actual_ecosystems
fail_with("#{dependabot_path} 缺少生态配置 #{missing_ecosystems.join(', ')}") unless missing_ecosystems.empty?

updates.each do |update|
  ecosystem = update["package-ecosystem"]
  fail_with("#{dependabot_path} #{ecosystem} 必须禁用常规版本更新 PR") unless update["open-pull-requests-limit"] == 0

  groups = update["groups"]
  security_grouped = groups.is_a?(Hash) && groups.values.any? do |group|
    group.is_a?(Hash) && group["applies-to"] == "security-updates" && group["patterns"] == ["*"]
  end
  fail_with("#{dependabot_path} #{ecosystem} 必须将安全更新分组") unless security_grouped
end

puts "PASS: Issue Forms、社区配置与 Dependabot 低噪音策略合法"
