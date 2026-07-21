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
rules = File.readlines(codeowners_path, chomp: true).map do |line|
  stripped = line.strip
  next if stripped.empty? || stripped.start_with?("#")

  tokens = stripped.split
  fail_with("CODEOWNERS 规则必须包含路径和至少一个所有者：#{stripped}") if tokens.length < 2
  pattern, *owners = tokens
  fail_with("CODEOWNERS 路径模式非法：#{pattern}") unless pattern == "*" || pattern.start_with?("/")
  owners.each do |owner|
    valid_owner = owner.match?(/\A@[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?\z/) || owner.match?(/\A[^\s@]+@[^\s@]+\.[^\s@]+\z/)
    fail_with("CODEOWNERS 所有者非法：#{owner}") unless valid_owner
  end
  [pattern, owners]
end.compact
fail_with("CODEOWNERS 缺少全仓默认所有者") unless rules.any? { |pattern, owners| pattern == "*" && owners.include?("@Boscodoggggg") }

puts "PASS: Issue Forms、社区配置与 CODEOWNERS 结构合法"
